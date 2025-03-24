package com.example.demo.service;

import com.example.demo.constant.PredefinedRole;
import com.example.demo.dto.request.UserCreationRequest;
import com.example.demo.dto.request.UserUpdateRequest;
import com.example.demo.dto.response.UserResponse;
import com.example.demo.entity.AttendanceRecords;
import com.example.demo.entity.Role;
import com.example.demo.entity.User;
import com.example.demo.exception.AppException;
import com.example.demo.exception.ErrorCode;
import com.example.demo.mapper.UserMapper;
import com.example.demo.repository.AttendanceRecordsRepository;
import com.example.demo.repository.RoleRepository;
import com.example.demo.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Slf4j
public class UserService {
    UserRepository userRepository;
    RoleRepository roleRepository;
    AttendanceRecordsRepository attendanceRecordsRepository;
    UserMapper userMapper;
    PasswordEncoder passwordEncoder;
    RedissonClient redissonClient;
    ObjectMapper objectMapper;

    public UserResponse createUser(UserCreationRequest request) {
        String cacheKey = "user:" + request.getUsername();
        String lockKey = "lock:user:" + request.getUsername();
        RLock lock = redissonClient.getLock(lockKey);
        boolean locked = false;

        try {
            locked = lock.tryLock(5, 10, TimeUnit.SECONDS);
            if (!locked) {
                throw new AppException(ErrorCode.UNCATEGORIZED_EXCEPTION);
            }

            if (userRepository.existsByUsername(request.getUsername())) {
                throw new AppException(ErrorCode.USER_EXISTED);
            }

            User user = userMapper.toUser(request);
            user.setPassword(passwordEncoder.encode(request.getPassword()));

            HashSet<Role> roles = new HashSet<>();
            roleRepository.findById(PredefinedRole.USER_ROLE).ifPresent(roles::add);
            user.setRoles(roles);

            try {
                user = userRepository.save(user);
            } catch (DataIntegrityViolationException exception) {
                throw new AppException(ErrorCode.USER_EXISTED);
            }

            RMapCache<String, String> mapCache = redissonClient.getMapCache("userCache");
            mapCache.remove(cacheKey);

            return userMapper.toUserResponse(user);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            if (locked) {
                lock.unlock();
            }
        }
    }


    public UserResponse getMyInfo() {
        var context = SecurityContextHolder.getContext();
        String name = context.getAuthentication().getName();

        String cacheKey = "userInfo:" + name;
        String lockKey = "lock:userInfo:" + name;
        RMapCache<String, String> mapCache = redissonClient.getMapCache("userCache");

        try {
            if (mapCache.containsKey(cacheKey)) {
                log.info("Fetching from Redis: {}", cacheKey);
                String jsonData = mapCache.get(cacheKey);
                return objectMapper.readValue(jsonData, UserResponse.class);
            }

            RLock lock = redissonClient.getLock(lockKey);
            boolean locked = false;

            try {
                locked = lock.tryLock(5, 10, TimeUnit.SECONDS);
                if (!locked) {
                    throw new AppException(ErrorCode.UNCATEGORIZED_EXCEPTION);
                }

                if (mapCache.containsKey(cacheKey)) {
                    log.info("Fetching from Redis after acquiring lock: {}", cacheKey);
                    String jsonData = mapCache.get(cacheKey);
                    return objectMapper.readValue(jsonData, UserResponse.class);
                }

                User user = userRepository.findByUsername(name)
                        .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));


                UserResponse response = UserResponse.builder()
                        .id(user.getId())
                        .username(user.getUsername())
                        .dob(user.getDob())
                        .lotus(user.getLotus())
                        .avatar(user.getAvatar())
                        .firstName(user.getFirstName())
                        .lastName(user.getLastName())
                        .build();

                String jsonData = objectMapper.writeValueAsString(response);
                mapCache.put(cacheKey, jsonData, 1, TimeUnit.DAYS);

                return response;
            } finally {
                if (locked) {
                    lock.unlock();
                }
            }
        } catch (Exception e) {
            log.error("Error processing user info", e);
            throw new AppException(ErrorCode.UNCATEGORIZED_EXCEPTION);
        }
    }


    @PostAuthorize("returnObject.username == authentication.name")
    public UserResponse updateUser(String userId, UserUpdateRequest request) {
        User user = userRepository.findById(userId).orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));

        userMapper.updateUser(user, request);
        user.setPassword(passwordEncoder.encode(request.getPassword()));

        var roles = roleRepository.findAllById(request.getRoles());
        user.setRoles(new HashSet<>(roles));

        return userMapper.toUserResponse(userRepository.save(user));
    }

    @PreAuthorize("hasRole('ADMIN')")
    public void deleteUser(String userId) {
        userRepository.deleteById(userId);
    }

    @PreAuthorize("hasRole('ADMIN')")
    public List<UserResponse> getUsers() {
        log.info("In method get Users");
        return userRepository.findAll().stream().map(userMapper::toUserResponse).toList();
    }

    @PreAuthorize("hasRole('ADMIN')")
    public UserResponse getUser(String id) {
        return userMapper.toUserResponse(
                userRepository.findById(id).orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED)));
    }
}

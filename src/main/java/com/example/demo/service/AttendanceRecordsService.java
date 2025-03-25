package com.example.demo.service;

import com.example.demo.dto.response.AttendanceRecordResponse;
import com.example.demo.dto.response.RewardHistoryResponse;
import com.example.demo.entity.AttendanceRecords;
import com.example.demo.entity.AttendanceReward;
import com.example.demo.entity.TimeFrame;
import com.example.demo.entity.User;
import com.example.demo.exception.AppException;
import com.example.demo.exception.ErrorCode;
import com.example.demo.repository.AttendanceRecordsRepository;
import com.example.demo.repository.AttendanceRewardRepository;
import com.example.demo.repository.TimeFrameRepository;
import com.example.demo.repository.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AttendanceRecordsService {
    AttendanceRecordsRepository attendanceRecordsRepository;
    UserRepository userRepository;
    TimeFrameRepository timeFrameRepository;
    AttendanceRewardRepository attendanceRewardRepository;
    RedissonClient redissonClient;
    ObjectMapper objectMapper;
    CacheService cacheService;

    static String CACHE_PREFIX = "attendance:";
    static long CACHE_TTL = 1;

    @Transactional
    public AttendanceRecords markAttendance() {
        var context = SecurityContextHolder.getContext();
        String name = context.getAuthentication().getName();
        User user = cacheService.getUserFromCache(name);

        LocalDate today = LocalDate.now();
        String cacheKey = "attendance:checked:" + user.getId() + ":" + today;
        RMapCache<String, String> mapCache = redissonClient.getMapCache("attendanceCache");

        if (Boolean.TRUE.toString().equals(mapCache.get(cacheKey))) {
            throw new AppException(ErrorCode.ALREADY_CHECKED);
        }

        String lockKey = "lock:attendance:" + user.getId() + ":" + today;
        RLock lock = redissonClient.getLock(lockKey);
        boolean locked = false;

        try {
            locked = lock.tryLock(5, 10, TimeUnit.SECONDS);
            if (!locked) {
                throw new AppException(ErrorCode.UNCATEGORIZED_EXCEPTION);
            }

            if (attendanceRecordsRepository.existsAttendanceRecordsByUserAndAndAttendanceDate(user, today)) {
                mapCache.put(cacheKey, Boolean.TRUE.toString(), 1, TimeUnit.DAYS);
                throw new AppException(ErrorCode.ALREADY_CHECKED);
            }

            List<TimeFrame> timeFrames = cacheService.getTimeFramesFromCache();
            LocalTime now = LocalTime.now();
            boolean isOnTime = timeFrames.stream()
                    .anyMatch(timeFrame -> !now.isBefore(timeFrame.getStart()) && now.isAfter(timeFrame.getEnd()));

            if (!isOnTime) {
                throw new AppException(ErrorCode.NOT_ON_TIME);
            }

            AttendanceReward attendanceReward = cacheService.getAttendanceRewardFromCache(today);

            AttendanceRecords record = AttendanceRecords.builder()
                    .user(user)
                    .attendanceDate(today)
                    .rewardAmount(attendanceReward.getRewardAmount())
                    .checkedInAt(LocalDateTime.now())
                    .build();
            user.setLotus(user.getLotus() + attendanceReward.getRewardAmount());
            userRepository.save(user);
            String jsonData = objectMapper.writeValueAsString(user);
            mapCache.put("user:" + name,jsonData , 1, TimeUnit.DAYS);
            mapCache.put(cacheKey, Boolean.TRUE.toString(), 1, TimeUnit.DAYS);
            mapCache.put("reward:" + user.getId() + ":" + today, Integer.toString(attendanceReward.getRewardAmount()), 1, TimeUnit.DAYS);

            mapCache.keySet().stream()
                    .filter(key -> key.contains(user.getId()) && key.contains("attendance"))
                    .forEach(mapCache::remove);
            mapCache.remove("rewardHistory:" + user.getId());

            log.info("Invalidated cache for user {} on date {}", user.getId(), today);

            return attendanceRecordsRepository.save(record);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Thread was interrupted", e);
            throw new AppException(ErrorCode.UNCATEGORIZED_EXCEPTION);
        } catch (Exception e) {
            log.error("Error processing attendance", e);
            throw new AppException(ErrorCode.UNCATEGORIZED_EXCEPTION);
        } finally {
            if (locked) {
                lock.unlock();
            }
        }
    }


    public List<AttendanceRecordResponse> getListChecking(LocalDate startDate, LocalDate endDate) {
        var context = SecurityContextHolder.getContext();
        String name = context.getAuthentication().getName();

        User user = cacheService.getUserFromCache(name);
        String cacheKey = CACHE_PREFIX + user.getId() + ":" + startDate + ":" + endDate;
        RMapCache<String, String> mapCache = redissonClient.getMapCache("attendanceCache");

        try {
            if (mapCache.containsKey(cacheKey)) {
                log.info("Fetching from Redis: {}", cacheKey);
                String jsonData = mapCache.get(cacheKey);
                return objectMapper.readValue(jsonData, new TypeReference<List<AttendanceRecordResponse>>() {});
            }

            String lockKey = "lock:" + cacheKey;
            RLock lock = redissonClient.getLock(lockKey);
            boolean locked = false;

            try {
                locked = lock.tryLock(5, 10, TimeUnit.SECONDS);
                if (!locked) {
                    log.info("Waiting for cache to be updated...");
                    Thread.sleep(500);
                    if (mapCache.containsKey(cacheKey)) {
                        return objectMapper.readValue(mapCache.get(cacheKey), new TypeReference<List<AttendanceRecordResponse>>() {});
                    } else {
                        throw new AppException(ErrorCode.UNCATEGORIZED_EXCEPTION);
                    }
                }

                log.info("Fetching from DB and caching: {}", cacheKey);
                Map<LocalDate, AttendanceRecords> attendanceRecords = attendanceRecordsRepository
                        .findByUserIdAndAttendanceDateBetween(user.getId(), startDate.toString(), endDate.toString())
                        .stream()
                        .collect(Collectors.toMap(AttendanceRecords::getAttendanceDate, record -> record));

                List<AttendanceRecordResponse> response = Stream.iterate(startDate, date -> date.plusDays(1))
                        .limit(ChronoUnit.DAYS.between(startDate, endDate) + 1)
                        .map(date -> {
                            AttendanceRecords attendanceRecord = attendanceRecords.get(date);
                            return AttendanceRecordResponse.builder()
                                    .isChecked(attendanceRecord != null)
                                    .attendanceDate(date)
                                    .rewardAmount(attendanceRecord != null ? attendanceRecord.getRewardAmount() : 0)
                                    .build();
                        })
                        .toList();

                String jsonData = objectMapper.writeValueAsString(response);
                mapCache.put(cacheKey, jsonData, CACHE_TTL, TimeUnit.DAYS);

                return response;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Thread was interrupted", e);
                throw new AppException(ErrorCode.UNCATEGORIZED_EXCEPTION);
            } catch (Exception e) {
                log.error("Error processing attendance history", e);
                throw new AppException(ErrorCode.UNCATEGORIZED_EXCEPTION);
            } finally {
                if (locked) {
                    lock.unlock();
                }
            }
        } catch (Exception e) {
            log.error("Error processing attendance history", e);
            throw new AppException(ErrorCode.UNCATEGORIZED_EXCEPTION);
        }
    }


    public List<RewardHistoryResponse> getAllRewardHistory() {
        var context = SecurityContextHolder.getContext();
        String name = context.getAuthentication().getName();

        User user = cacheService.getUserFromCache(name);
        String cacheKey = "rewardHistory:" + user.getId();
        RMapCache<String, String> mapCache = redissonClient.getMapCache("rewardCache");

        try {
            if (mapCache.containsKey(cacheKey)) {
                log.info("Fetching from Redis: {}", cacheKey);
                String jsonData = mapCache.get(cacheKey);
                return objectMapper.readValue(jsonData, new TypeReference<List<RewardHistoryResponse>>() {});
            }

            String lockKey = "lock:rewardHistory:" + user.getId();
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
                    return objectMapper.readValue(jsonData, new TypeReference<List<RewardHistoryResponse>>() {});
                }

                log.info("Fetching from DB and caching: {}", cacheKey);
                List<AttendanceRecords> records = attendanceRecordsRepository.findByUserId(user.getId());

                List<RewardHistoryResponse> response = records.stream()
                        .map(record -> new RewardHistoryResponse(record.getAttendanceDate(), record.getRewardAmount()))
                        .sorted(Comparator.comparing(RewardHistoryResponse::getDate).reversed())
                        .toList();

                String jsonData = objectMapper.writeValueAsString(response);
                mapCache.put(cacheKey, jsonData, CACHE_TTL, TimeUnit.DAYS);

                return response;
            } finally {
                if (locked) {
                    lock.unlock();
                }
            }
        } catch (Exception e) {
            log.error("Error processing reward history", e);
            throw new AppException(ErrorCode.UNCATEGORIZED_EXCEPTION);
        }
    }

}

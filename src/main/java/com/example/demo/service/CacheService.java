package com.example.demo.service;

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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
@Slf4j
public class CacheService {
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    private final TimeFrameRepository timeFrameRepository;
    private final AttendanceRewardRepository
            attendanceRewardRepository;
    private static long CACHE_TTL = 1;

    public  <T> T getCacheAndLock(String cacheKey, TypeReference<T> typeRef, Supplier<T> dbFetcher) {
        RMapCache<String, String> mapCache = redissonClient.getMapCache("attendanceCache");
        try {
            if (mapCache.containsKey(cacheKey)) {
                return objectMapper.readValue(mapCache.get(cacheKey), typeRef);
            }
            String lockKey = "lock:" + cacheKey;
            var lock = redissonClient.getLock(lockKey);
            if (lock.tryLock(5, 10, TimeUnit.SECONDS)) {
                if (mapCache.containsKey(cacheKey)) {
                    return objectMapper.readValue(mapCache.get(cacheKey), typeRef);
                }
                T data = dbFetcher.get();
                String jsonData = objectMapper.writeValueAsString(data);
                mapCache.put(cacheKey, jsonData, CACHE_TTL, TimeUnit.DAYS);

                return data;
            } else {
                Thread.sleep(100);
                if (mapCache.containsKey(cacheKey)) {
                    return objectMapper.readValue(mapCache.get(cacheKey), typeRef);
                }
            }
        } catch (Exception e) {
            throw new AppException(ErrorCode.UNCATEGORIZED_EXCEPTION);
        }
        throw new AppException(ErrorCode.UNCATEGORIZED_EXCEPTION);
    }

    public User getUserFromCache(String username) {
        String cacheKey = "user:" + username;
        RMapCache<String, String> userCache = redissonClient.getMapCache("userCache");

        if (userCache.containsKey(cacheKey)) {
            try {
                log.info("Fetching user from cache: {}", username);
                return objectMapper.readValue(userCache.get(cacheKey), User.class);
            } catch (Exception e) {
                log.error("Failed to parse cached user", e);
            }
        }

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));

        try {
            userCache.put(cacheKey, objectMapper.writeValueAsString(user), 10, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.error("Failed to cache user", e);
        }

        return user;
    }

    public List<TimeFrame> getTimeFramesFromCache() {
        String cacheKey = "timeFrames";
        RMapCache<String, String> cache = redissonClient.getMapCache("timeFrameCache");

        if (cache.containsKey(cacheKey)) {
            try {
                log.info("Fetching timeFrames from cache");
                return objectMapper.readValue(cache.get(cacheKey), new TypeReference<List<TimeFrame>>() {});
            } catch (Exception e) {
                log.error("Failed to parse cached timeFrames", e);
            }
        }

        List<TimeFrame> timeFrames = timeFrameRepository.findAll();

        try {
            cache.put(cacheKey, objectMapper.writeValueAsString(timeFrames), 10, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.error("Failed to cache timeFrames", e);
        }

        return timeFrames;
    }

    public AttendanceReward getAttendanceRewardFromCache(LocalDate today) {
        String cacheKey = "reward:" + today;
        RMapCache<String, String> cache = redissonClient.getMapCache("rewardCache");

        if (cache.containsKey(cacheKey)) {
            try {
                log.info("Fetching attendanceReward from cache: {}", today);
                return objectMapper.readValue(cache.get(cacheKey), AttendanceReward.class);
            } catch (Exception e) {
                log.error("Failed to parse cached attendanceReward", e);
            }
        }

        AttendanceReward reward = attendanceRewardRepository.findAttendanceRewardsByRewardDate(today)
                .orElseThrow(() -> new AppException(ErrorCode.REWARD_NOT_FOUND));

        try {
            cache.put(cacheKey, objectMapper.writeValueAsString(reward), 1, TimeUnit.DAYS);
        } catch (Exception e) {
            log.error("Failed to cache attendanceReward", e);
        }

        return reward;
    }




}

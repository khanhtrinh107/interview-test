package com.example.demo.service;

import com.example.demo.exception.AppException;
import com.example.demo.exception.ErrorCode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class CacheService {
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
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

}

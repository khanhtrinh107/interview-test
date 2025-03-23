package com.example.demo.service;

import lombok.RequiredArgsConstructor;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CacheService {
    private final RedissonClient redissonClient;

    public void saveToCache(String key, String value, long ttlInSeconds) {
        RMapCache<String, String> mapCache = redissonClient.getMapCache("mycache");
        mapCache.put(key, value);
    }

    public String getFromCache(String key) {
        RMapCache<String, String> mapCache = redissonClient.getMapCache("mycache");
        return mapCache.get(key);
    }

}

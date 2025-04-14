package com.example.payment.service;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CacheService {

    private final RedisTemplate<String, Object> redisTemplate;

    public void cacheData(String key, Object value, long ttlSeconds) {
        redisTemplate.opsForValue().set(key, value, ttlSeconds, TimeUnit.SECONDS);
    }

    public Object getCachedData(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    public boolean hasKey(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    public void deleteCache(String key) {
        redisTemplate.delete(key);
    }
}
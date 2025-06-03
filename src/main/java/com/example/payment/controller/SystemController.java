package com.example.payment.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/system")
@Slf4j
public class SystemController {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @GetMapping("/redis-test")
    public String testRedisConnection() {
        try {
            String key = "test:key";
            String value = "Hello Redis " + System.currentTimeMillis();

            log.debug("Attempting to store in Redis: {}={}", key, value);
            redisTemplate.opsForValue().set(key, value);

            Object retrieved = redisTemplate.opsForValue().get(key);
            log.debug("Retrieved from Redis: {}", retrieved);

            return "Redis test successful! Stored: " + value + ", Retrieved: " + retrieved;
        } catch (Exception e) {
            log.error("Redis test failed", e);
            return "Redis test failed: " + e.getMessage();
        }
    }
}
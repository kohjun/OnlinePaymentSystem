package com.example.payment.infrastructure.util;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimiterTest {

    @Test
    void productionModeFailsClosedWhenRedisIsUnavailable() {
        RateLimiter limiter = limiter(true);

        assertFalse(limiter.allowRequest("auction:EVT-1:CUST-1"));
    }

    @Test
    void developmentModeCanUseLocalBucketWhenRedisIsUnavailable() {
        RateLimiter limiter = limiter(false);

        assertTrue(limiter.allowRequest("auction:EVT-1:CUST-1"));
    }

    @SuppressWarnings("unchecked")
    private RateLimiter limiter(boolean failClosed) {
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.increment(any(), anyLong())).thenThrow(new IllegalStateException("Redis unavailable"));
        RateLimiter limiter = new RateLimiter(redisTemplate);
        ReflectionTestUtils.setField(limiter, "capacity", 10);
        ReflectionTestUtils.setField(limiter, "refillRate", 2);
        ReflectionTestUtils.setField(limiter, "clientWindowSeconds", 60L);
        ReflectionTestUtils.setField(limiter, "clientMaxRequests", 120L);
        ReflectionTestUtils.setField(limiter, "failClosed", failClosed);
        return limiter;
    }
}

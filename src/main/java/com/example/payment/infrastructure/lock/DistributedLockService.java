package com.example.payment.infrastructure.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
@Slf4j
public class DistributedLockService {

    private final RedisTemplate<String, Object> redisTemplate;

    // --- [테스트 제어 플래그] ---
    // true: 경쟁 테스트 (Lock 사용 및 1초 타임아웃 적용)
    // false: 최대 TPS 측정 (Lock 미사용)
    private final boolean isLockEnabled = true; // 현재 최대 TPS 측정을 위해 false 설정

    // Lock 대기 시간 설정 (경쟁 테스트 시 사용)
    private static final long DEFAULT_SLEEP_TIME_MILLIS = 10;
    private static final long DEFAULT_WAIT_TIME_SECONDS = 1; // Lock 경쟁 시 빠르게 실패 유도
    private static final long DEFAULT_LEASE_TIME_SECONDS = 5;
    private static final String LOCK_PREFIX = "lock:";


    public <T> T executeWithLock(String resourceId, Supplier<T> supplier) {
        if (!isLockEnabled) {
            // Lock 미사용 시나리오 (최대 TPS 측정)
            return supplier.get();
        }

        // Lock 사용 시나리오 (경쟁 테스트)
        String lockKey = LOCK_PREFIX + resourceId;
        String lockValue = UUID.randomUUID().toString();
        long endTime = System.currentTimeMillis() + (DEFAULT_WAIT_TIME_SECONDS * 1000);

        try {
            // 1. Lock 획득 시도
            while (System.currentTimeMillis() < endTime) {
                Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                        lockKey,
                        lockValue,
                        DEFAULT_LEASE_TIME_SECONDS,
                        TimeUnit.SECONDS
                );

                if (Boolean.TRUE.equals(acquired)) {
                    log.info("Lock acquired successfully: key={}", lockKey);
                    return supplier.get();
                }

                // 락 획득 실패 시 잠시 대기 후 재시도
                TimeUnit.MILLISECONDS.sleep(DEFAULT_SLEEP_TIME_MILLIS);
            }

            // 2. Lock 획득 타임아웃
            log.warn("Lock acquisition timeout: key={}, waited={}ms", lockKey, DEFAULT_WAIT_TIME_SECONDS * 1000);
            throw new LockAcquisitionException("Failed to acquire lock: " + resourceId + " within " + DEFAULT_WAIT_TIME_SECONDS + " seconds");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            releaseLock(lockKey, lockValue);
            throw new LockAcquisitionException("Interrupted while waiting for lock: " + resourceId);
        } finally {
            // 3. Lock 해제
            releaseLock(lockKey, lockValue);
        }
    }

    private void releaseLock(String key, String expectedValue) {
        if (!isLockEnabled) return;
        // Lock 해제 로직
        redisTemplate.delete(key);
    }

    // Lock 획득 실패 시 발생하는 예외 클래스
    public static class LockAcquisitionException extends RuntimeException {
        public LockAcquisitionException(String message) {
            super(message);
        }
    }
}
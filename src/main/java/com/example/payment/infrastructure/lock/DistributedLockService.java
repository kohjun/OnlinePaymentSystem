package com.example.payment.infrastructure.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 분산 락 서비스 - Redis 기반
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DistributedLockService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String LOCK_PREFIX = "lock:";
    private static final long DEFAULT_LOCK_TIMEOUT_SECONDS = 10;
    private static final long DEFAULT_WAIT_TIMEOUT_SECONDS = 5;

    /**
     * 락 획득 후 작업 실행 (자동 해제)
     */
    public <T> T executeWithLock(String lockKey, Supplier<T> supplier) {
        return executeWithLock(lockKey, DEFAULT_LOCK_TIMEOUT_SECONDS,
                DEFAULT_WAIT_TIMEOUT_SECONDS, supplier);
    }

    /**
     * 락 획득 후 작업 실행 (타임아웃 지정)
     */
    public <T> T executeWithLock(String lockKey, long lockTimeoutSeconds,
                                 long waitTimeoutSeconds, Supplier<T> supplier) {
        String fullLockKey = LOCK_PREFIX + lockKey;
        String lockValue = UUID.randomUUID().toString();

        try {
            if (!acquireLockWithRetry(fullLockKey, lockValue, lockTimeoutSeconds, waitTimeoutSeconds)) {
                throw new LockAcquisitionException(
                        String.format("Failed to acquire lock: %s within %d seconds",
                                lockKey, waitTimeoutSeconds)
                );
            }

            log.debug("Lock acquired: key={}, value={}", lockKey, lockValue);
            T result = supplier.get();
            log.debug("Work completed under lock: key={}", lockKey);
            return result;

        } finally {
            // 내부 private 메서드 호출
            releaseInternalLock(fullLockKey, lockValue);
        }
    }

    /**
     * 락 획득 (재시도 포함)
     */
    private boolean acquireLockWithRetry(String fullLockKey, String lockValue,
                                         long lockTimeoutSeconds, long waitTimeoutSeconds) {
        long startTime = System.currentTimeMillis();
        long waitTimeoutMillis = waitTimeoutSeconds * 1000;

        while (true) {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                    fullLockKey,
                    lockValue,
                    lockTimeoutSeconds,
                    TimeUnit.SECONDS
            );

            if (Boolean.TRUE.equals(acquired)) {
                return true;
            }

            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed >= waitTimeoutMillis) {
                log.warn("Lock acquisition timeout: key={}, waited={}ms",
                        fullLockKey, elapsed);
                return false;
            }

            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Lock acquisition interrupted: key={}", fullLockKey);
                return false;
            }
        }
    }

    /**
     * 내부 락 해제 메서드 (Lua 스크립트 사용)
     */
    private void releaseInternalLock(String fullLockKey, String lockValue) {
        try {
            String luaScript =
                    "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                            "    return redis.call('del', KEYS[1]) " +
                            "else " +
                            "    return 0 " +
                            "end";

            Long result = redisTemplate.execute(
                    new org.springframework.data.redis.core.script.DefaultRedisScript<>(luaScript, Long.class),
                    java.util.Collections.singletonList(fullLockKey),
                    lockValue
            );

            if (result != null && result == 1) {
                log.debug("Lock released: key={}", fullLockKey);
            } else {
                log.warn("Lock was already released or expired: key={}", fullLockKey);
            }

        } catch (Exception e) {
            log.error("Error releasing lock: key={}", fullLockKey, e);
        }
    }

    /**
     * 단순 락 획득 (수동 해제 필요)
     */
    public String acquireLock(String lockKey) {
        return acquireLock(lockKey, DEFAULT_LOCK_TIMEOUT_SECONDS);
    }

    /**
     * 단순 락 획득 (타임아웃 지정)
     */
    public String acquireLock(String lockKey, long timeoutSeconds) {
        String fullLockKey = LOCK_PREFIX + lockKey;
        String lockValue = UUID.randomUUID().toString();

        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                fullLockKey,
                lockValue,
                timeoutSeconds,
                TimeUnit.SECONDS
        );

        if (Boolean.TRUE.equals(acquired)) {
            log.debug("Lock acquired manually: key={}", lockKey);
            return lockValue;
        }

        log.debug("Failed to acquire lock: key={}", lockKey);
        return null;
    }

    /**
     * 수동 락 해제 - PUBLIC 메서드
     * 외부에서 acquireLock으로 획득한 락을 해제할 때 사용
     */
    public void releaseLock(String lockKey, String lockValue) {
        String fullLockKey = LOCK_PREFIX + lockKey;
        // 내부 메서드 호출
        releaseInternalLock(fullLockKey, lockValue);
    }

    /**
     * 락 상태 확인
     */
    public boolean isLocked(String lockKey) {
        String fullLockKey = LOCK_PREFIX + lockKey;
        return Boolean.TRUE.equals(redisTemplate.hasKey(fullLockKey));
    }

    /**
     * 락 강제 해제 (관리자용)
     */
    public void forceReleaseLock(String lockKey) {
        String fullLockKey = LOCK_PREFIX + lockKey;
        redisTemplate.delete(fullLockKey);
        log.warn("Lock forcefully released: key={}", lockKey);
    }

    /**
     * 락 획득 예외
     */
    public static class LockAcquisitionException extends RuntimeException {
        public LockAcquisitionException(String message) {
            super(message);
        }
    }
}
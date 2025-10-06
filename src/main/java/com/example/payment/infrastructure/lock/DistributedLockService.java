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
 *
 * 단일 책임: 분산 환경에서 동시성 제어만 담당
 * - 락 획득
 * - 락 해제
 * - 자동 만료 관리
 *
 * 비즈니스 로직은 포함하지 않음!
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DistributedLockService {

    private final RedisTemplate<String, Object> redisTemplate;

    // 락 기본 설정
    private static final String LOCK_PREFIX = "lock:";
    private static final long DEFAULT_LOCK_TIMEOUT_SECONDS = 10;
    private static final long DEFAULT_WAIT_TIMEOUT_SECONDS = 5;

    /**
     * 락 획득 후 작업 실행 (자동 해제)
     *
     * @param lockKey 락 키 (예: "inventory:PROD-001")
     * @param supplier 락 획득 후 실행할 작업
     * @return 작업 결과
     * @throws LockAcquisitionException 락 획득 실패 시
     */
    public <T> T executeWithLock(String lockKey, Supplier<T> supplier) {
        return executeWithLock(lockKey, DEFAULT_LOCK_TIMEOUT_SECONDS,
                DEFAULT_WAIT_TIMEOUT_SECONDS, supplier);
    }

    /**
     * 락 획득 후 작업 실행 (타임아웃 지정)
     *
     * @param lockKey 락 키
     * @param lockTimeoutSeconds 락 자동 만료 시간
     * @param waitTimeoutSeconds 락 대기 시간
     * @param supplier 실행할 작업
     * @return 작업 결과
     */
    public <T> T executeWithLock(String lockKey, long lockTimeoutSeconds,
                                 long waitTimeoutSeconds, Supplier<T> supplier) {
        String fullLockKey = LOCK_PREFIX + lockKey;
        String lockValue = UUID.randomUUID().toString(); // 락 소유자 식별

        try {
            // 1. 락 획득 시도 (재시도 포함)
            if (!acquireLockWithRetry(fullLockKey, lockValue, lockTimeoutSeconds, waitTimeoutSeconds)) {
                throw new LockAcquisitionException(
                        String.format("Failed to acquire lock: %s within %d seconds",
                                lockKey, waitTimeoutSeconds)
                );
            }

            log.debug("Lock acquired: key={}, value={}", lockKey, lockValue);

            // 2. 작업 실행
            T result = supplier.get();

            log.debug("Work completed under lock: key={}", lockKey);
            return result;

        } finally {
            // 3. 락 해제 (항상 실행)
            releaseLock(fullLockKey, lockValue);
        }
    }

    /**
     * 락 획득 (재시도 포함)
     *
     * @return 획득 성공 여부
     */
    private boolean acquireLockWithRetry(String fullLockKey, String lockValue,
                                         long lockTimeoutSeconds, long waitTimeoutSeconds) {
        long startTime = System.currentTimeMillis();
        long waitTimeoutMillis = waitTimeoutSeconds * 1000;

        while (true) {
            // 락 획득 시도
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                    fullLockKey,
                    lockValue,
                    lockTimeoutSeconds,
                    TimeUnit.SECONDS
            );

            if (Boolean.TRUE.equals(acquired)) {
                return true; // 성공
            }

            // 대기 시간 초과 확인
            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed >= waitTimeoutMillis) {
                log.warn("Lock acquisition timeout: key={}, waited={}ms",
                        fullLockKey, elapsed);
                return false;
            }

            // 짧은 대기 후 재시도
            try {
                Thread.sleep(50); // 50ms 대기
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Lock acquisition interrupted: key={}", fullLockKey);
                return false;
            }
        }
    }

    /**
     * 락 해제 (소유자만 해제 가능)
     *
     * @param fullLockKey 전체 락 키
     * @param lockValue 락 소유자 식별값
     */
    private void releaseLock(String fullLockKey, String lockValue) {
        try {
            // Lua 스크립트로 원자적 해제 (소유자 확인 + 삭제)
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
            // 락 해제 실패는 자동 만료로 처리됨
        }
    }

    /**
     * 단순 락 획득 (수동 해제 필요)
     *
     * @param lockKey 락 키
     * @return 락 소유자 ID (해제 시 필요) 또는 null (실패)
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
     * 수동 락 해제
     *
     * @param lockKey 락 키
     * @param lockValue 락 소유자 ID
     */
    public void releaseLock(String lockKey, String lockValue) {
        String fullLockKey = LOCK_PREFIX + lockKey;
        releaseLock(fullLockKey, lockValue);
    }

    /**
     * 락 상태 확인
     *
     * @param lockKey 락 키
     * @return 락이 존재하는지 여부
     */
    public boolean isLocked(String lockKey) {
        String fullLockKey = LOCK_PREFIX + lockKey;
        return Boolean.TRUE.equals(redisTemplate.hasKey(fullLockKey));
    }

    /**
     * 락 강제 해제 (관리자용, 위험!)
     *
     * @param lockKey 락 키
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
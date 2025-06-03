package com.example.payment.util;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 슬라이딩 윈도우 알고리즘을 사용한 클라이언트별 속도 제한기
 * Redis와 로컬 캐시를 조합하여 분산 환경에서도 효율적으로 동작
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RateLimiter {

    private final RedisTemplate<String, Object> redisTemplate;

    // 로컬 캐시를 통한 최적화 (Redis 부하 감소용)
    private final ConcurrentHashMap<String, TokenBucket> localBuckets = new ConcurrentHashMap<>();

    // 기본 설정값
    private static final int DEFAULT_CAPACITY = 10; // 초당 최대 요청 수
    private static final int DEFAULT_REFILL_RATE = 2; // 초당 충전되는 토큰 수


    /**
     * 클라이언트 요청의 허용 여부 결정
     * @param clientId 요청 클라이언트 식별자
     * @return 요청 허용 여부
     */
    public boolean allowRequest(String clientId) {
        // 클라이언트 등급에 따른 토큰 버킷 파라미터 선택
        int capacity =  DEFAULT_CAPACITY;
        int refillRate =  DEFAULT_REFILL_RATE;

        // 로컬 캐시에서 버킷 확인
        TokenBucket bucket = localBuckets.computeIfAbsent(clientId,
                k -> new TokenBucket(capacity, refillRate));

        // 글로벌 속도 제한 먼저 확인 (시스템 과부하 방지용)
        String globalRateKey = "ratelimit:global";
        boolean globalAllowed = checkGlobalRateLimit(globalRateKey);

        if (!globalAllowed) {
            log.warn("Global rate limit exceeded");
            return false;
        }

        // 로컬 버킷 상태 갱신
        bucket.refill();

        // 토큰 사용 가능 여부 확인
        if (bucket.tryConsume()) {
            // Redis에 클라이언트별 사용량 기록 (분산 환경 동기화용)
            String redisKey = "ratelimit:" + clientId;
            Long currentCount = redisTemplate.opsForValue().increment(redisKey, 1);

            // 키 만료 설정 (첫 요청인 경우)
            if (currentCount != null && currentCount == 1) {
                redisTemplate.expire(redisKey, Duration.ofSeconds(60));
            }

            return true;
        }

        log.warn("Rate limit exceeded for client: {}", clientId);
        return false;
    }

    /**
     * 전체 시스템 부하 관리를 위한 글로벌 속도 제한
     */
    private boolean checkGlobalRateLimit(String key) {
        Long currentValue = redisTemplate.opsForValue().increment(key, 1);

        // 키 만료 설정 (첫 요청인 경우)
        if (currentValue != null && currentValue == 1) {
            redisTemplate.expire(key, Duration.ofSeconds(1)); // 1초 윈도우
        }

        // 글로벌 제한: 초당 1000 요청
        return currentValue != null && currentValue <= 1000;
    }

    /**
     * 클라이언트 등급 확인 (실제 구현에서는 DB 조회 등 필요)
     */
    private boolean isPremuimClient(String clientId) {
        // 실제 구현에서는 사용자 서비스 또는 DB에서 클라이언트 등급 조회
        return clientId != null && clientId.startsWith("premium-");
    }

    /**
     * 토큰 버킷 알고리즘 구현
     * - 일정 시간마다 토큰이 충전됨
     * - 요청마다 토큰을 소비
     * - 토큰이 없으면 요청 거부
     */
    private static class TokenBucket {
        private final int capacity;
        private final int refillRate;
        private int tokens;
        private Instant lastRefillTime;

        public TokenBucket(int capacity, int refillRate) {
            this.capacity = capacity;
            this.refillRate = refillRate;
            this.tokens = capacity; // 초기 상태는 가득 참
            this.lastRefillTime = Instant.now();
        }

        public synchronized void refill() {
            Instant now = Instant.now();
            long secondsSinceLastRefill = Duration.between(lastRefillTime, now).getSeconds();

            if (secondsSinceLastRefill > 0) {
                // 경과 시간에 비례해 토큰 충전
                int tokensToAdd = (int) (secondsSinceLastRefill * refillRate);
                tokens = Math.min(capacity, tokens + tokensToAdd);
                lastRefillTime = now;
            }
        }

        public synchronized boolean tryConsume() {
            if (tokens > 0) {
                tokens--;
                return true;
            }
            return false;
        }
    }
}
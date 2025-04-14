package com.example.payment.util;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
public class RateLimiter {

    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    // 기본 설정: 초당 10개의 요청 허용, 최대 버킷 크기 20
    private static final long DEFAULT_TOKENS_PER_SECOND = 10;
    private static final long DEFAULT_MAX_BUCKET_SIZE = 20;

    public boolean allowRequest(String clientId) {
        TokenBucket bucket = buckets.computeIfAbsent(clientId,
                id -> new TokenBucket(DEFAULT_TOKENS_PER_SECOND, DEFAULT_MAX_BUCKET_SIZE));
        return bucket.tryConsume();
    }

    private static class TokenBucket {
        private final long tokensPerSecond;
        private final long maxBucketSize;
        private long currentTokens;
        private Instant lastRefillTimestamp;

        public TokenBucket(long tokensPerSecond, long maxBucketSize) {
            this.tokensPerSecond = tokensPerSecond;
            this.maxBucketSize = maxBucketSize;
            this.currentTokens = maxBucketSize;
            this.lastRefillTimestamp = Instant.now();
        }

        public synchronized boolean tryConsume() {
            refill();

            if (currentTokens > 0) {
                currentTokens--;
                return true;
            }

            return false;
        }

        private void refill() {
            Instant now = Instant.now();
            Duration timeSinceLastRefill = Duration.between(lastRefillTimestamp, now);
            long tokensToAdd = (timeSinceLastRefill.toMillis() * tokensPerSecond) / 1000;

            if (tokensToAdd > 0) {
                currentTokens = Math.min(currentTokens + tokensToAdd, maxBucketSize);
                lastRefillTimestamp = now;
            }
        }
    }
}
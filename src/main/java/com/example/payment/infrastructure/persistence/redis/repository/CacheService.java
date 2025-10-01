package com.example.payment.infrastructure.persistence.redis.repository;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
@Slf4j
@RequiredArgsConstructor
public class CacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final Map<String, CacheEntry> localCache = new ConcurrentHashMap<>();

    /**
     * Redis 연결 상태 확인
     */
    public boolean isRedisConnected() {
        try {
            return Boolean.TRUE.equals(redisTemplate.execute(connection -> {
                connection.ping();
                return true;
            }, true));
        } catch (Exception e) {
            log.error("Redis connection check failed: {}", e.getMessage());
            return false;
        }
    }

    // ========================================
    // 일반 데이터 캐싱 (Value 구조)
    // ========================================

    /**
     * 데이터 저장 (Value)
     */
    public void cacheData(String key, Object value, long ttlSeconds) {
        try {
            redisTemplate.opsForValue().set(key, value, ttlSeconds, TimeUnit.SECONDS);
            localCache.put(key, new CacheEntry(value, System.currentTimeMillis() + (ttlSeconds * 1000)));
            log.debug("Data cached: key={}", key);
        } catch (Exception e) {
            log.error("Redis cache failed for key: {}, using local cache only", key, e);
            localCache.put(key, new CacheEntry(value, System.currentTimeMillis() + (ttlSeconds * 1000)));
        }
    }

    /**
     * 데이터 조회 (Value) - 2단계 캐싱
     */
    public Object getCachedData(String key) {
        try {
            // 1. Redis 조회
            Object value = redisTemplate.opsForValue().get(key);
            if (value != null) {
                log.debug("Cache hit (Redis): key={}", key);
                // 로컬 캐시 동기화
                localCache.put(key, new CacheEntry(value, System.currentTimeMillis() + 300_000));
                return value;
            }

            // 2. 로컬 캐시 폴백
            log.debug("Cache miss (Redis), checking local cache: key={}", key);
            CacheEntry localEntry = localCache.get(key);
            if (localEntry != null && !localEntry.isExpired()) {
                log.debug("Cache hit (Local): key={}", key);
                return localEntry.getValue();
            }

            log.debug("Cache miss: key={}", key);
            return null;

        } catch (Exception e) {
            log.error("Redis error for key: {}, using local cache", key, e);
            CacheEntry localEntry = localCache.get(key);
            return localEntry != null && !localEntry.isExpired() ? localEntry.getValue() : null;
        }
    }

    // ========================================
    // Hash 데이터 캐싱 (재고 정보용)
    // ========================================

    /**
     * Hash 데이터 저장
     */
    public void cacheMapData(String key, Map<String, Object> map, long ttlSeconds) {
        try {
            redisTemplate.opsForHash().putAll(key, map);
            redisTemplate.expire(key, ttlSeconds, TimeUnit.SECONDS);
            localCache.put(key, new CacheEntry(map, System.currentTimeMillis() + (ttlSeconds * 1000)));
            log.debug("Map data cached: key={}", key);
        } catch (Exception e) {
            log.error("Redis hash cache failed for key: {}, using local cache only", key, e);
            localCache.put(key, new CacheEntry(map, System.currentTimeMillis() + (ttlSeconds * 1000)));
        }
    }

    /**
     * Hash 데이터 조회 (수정됨 - 핵심!)
     */
    public Map<String, Object> getCachedMapData(String key) {
        try {
            // 1. Redis Hash 조회
            Map<Object, Object> redisMap = redisTemplate.opsForHash().entries(key);
            if (!redisMap.isEmpty()) {
                // Object -> String 변환
                Map<String, Object> result = new HashMap<>();
                redisMap.forEach((k, v) -> result.put(k.toString(), v));

                log.debug("Cache hit (Redis Hash): key={}", key);
                localCache.put(key, new CacheEntry(result, System.currentTimeMillis() + 300_000));
                return result;
            }

            // 2. 로컬 캐시 폴백
            log.debug("Cache miss (Redis), checking local cache: key={}", key);
            CacheEntry localEntry = localCache.get(key);
            if (localEntry != null && !localEntry.isExpired()) {
                log.debug("Cache hit (Local): key={}", key);
                return (Map<String, Object>) localEntry.getValue();
            }

            log.debug("Cache miss: key={}", key);
            return null;

        } catch (Exception e) {
            log.error("Redis error for key: {}, using local cache", key, e);
            CacheEntry localEntry = localCache.get(key);
            return localEntry != null && !localEntry.isExpired() ?
                    (Map<String, Object>) localEntry.getValue() : null;
        }
    }

    // ========================================
    // Cache-Aside 패턴 (DB 연동)
    // ========================================

    /**
     * 캐시 조회 or DB 로드 (Value)
     */
    public <T> T getOrLoad(String key, Supplier<T> dbLoader, long ttlSeconds) {
        Object cached = getCachedData(key);
        if (cached != null) {
            return (T) cached;
        }

        log.debug("Cache miss, loading from DB: key={}", key);
        T value = dbLoader.get();
        if (value != null) {
            cacheData(key, value, ttlSeconds);
        }
        return value;
    }

    /**
     * 캐시 조회 or DB 로드 (Hash)
     */
    public Map<String, Object> getOrLoadMap(String key, Supplier<Map<String, Object>> dbLoader, long ttlSeconds) {
        Map<String, Object> cached = getCachedMapData(key);
        if (cached != null) {
            return cached;
        }

        log.debug("Cache miss, loading from DB: key={}", key);
        Map<String, Object> value = dbLoader.get();
        if (value != null) {
            cacheMapData(key, value, ttlSeconds);
        }
        return value;
    }

    // ========================================
    // 기존 메서드 유지
    // ========================================

    public boolean hasKey(String key) {
        try {
            boolean exists = Boolean.TRUE.equals(redisTemplate.hasKey(key));
            if (exists) return true;

            CacheEntry localEntry = localCache.get(key);
            return localEntry != null && !localEntry.isExpired();
        } catch (Exception e) {
            log.error("Error checking key: {}", key, e);
            CacheEntry localEntry = localCache.get(key);
            return localEntry != null && !localEntry.isExpired();
        }
    }

    public void deleteCache(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.error("Error deleting from Redis: {}", key, e);
        } finally {
            localCache.remove(key);
        }
    }

    public boolean confirmReservation(String reservationId) {
        // 기존 로직 유지
        String reservationKey = "reservation:" + reservationId;
        try {
            Map<Object, Object> reservationData = redisTemplate.opsForHash().entries(reservationKey);
            if (reservationData.isEmpty()) return false;

            String status = (String) reservationData.get("status");
            if ("CONFIRMED".equals(status)) return true;
            if ("CANCELLED".equals(status)) return false;

            redisTemplate.opsForHash().put(reservationKey, "status", "CONFIRMED");
            String resourceKey = (String) reservationData.get("resource_id");
            redisTemplate.opsForHash().increment(resourceKey, "reserved",
                    -Integer.parseInt(reservationData.get("quantity").toString()));

            return true;
        } catch (Exception e) {
            log.error("Error confirming reservation: {}", reservationId, e);
            return false;
        }
    }

    public boolean cancelReservation(String reservationId) {
        // 기존 로직 유지 (위와 동일 패턴)
        String reservationKey = "reservation:" + reservationId;
        try {
            Map<Object, Object> reservationData = redisTemplate.opsForHash().entries(reservationKey);
            if (reservationData.isEmpty()) return false;

            String status = (String) reservationData.get("status");
            if ("CANCELLED".equals(status)) return true;
            if ("CONFIRMED".equals(status)) return false;

            redisTemplate.opsForHash().put(reservationKey, "status", "CANCELLED");
            String resourceKey = (String) reservationData.get("resource_id");
            redisTemplate.opsForHash().increment(resourceKey, "reserved",
                    -Integer.parseInt(reservationData.get("quantity").toString()));

            return true;
        } catch (Exception e) {
            log.error("Error cancelling reservation: {}", reservationId, e);
            return false;
        }
    }

    // ========================================
    // 내부 클래스
    // ========================================

    private static class CacheEntry {
        private final Object value;
        private final long expiryTime;

        public CacheEntry(Object value, long expiryTime) {
            this.value = value;
            this.expiryTime = expiryTime;
        }

        public Object getValue() {
            return value;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > expiryTime;
        }
    }
}
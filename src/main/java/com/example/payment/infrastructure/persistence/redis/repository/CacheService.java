package com.example.payment.infrastructure.persistence.redis.repository;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 결제 처리를 위한 향상된 캐시 서비스
 * - TTL 기반 예약 지원
 * - 로컬 백업을 통해 Redis 연결 실패 처리
 * - 일관성을 위한 원자적 작업 포함
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CacheService {

    private final RedisTemplate<String, Object> redisTemplate;

    // 인메모리 캐시 백업
    private final Map<String, CacheEntry> localCache = new ConcurrentHashMap<>();

    // 원자 예약 작업을 위한 Lua 스크립트
    private static final String RESERVE_SCRIPT =
        "local current = redis.call('HGETALL', KEYS[1]) " +
                "local quantity = tonumber(current['quantity'] or 0) " +
                "local reserved = tonumber(current['reserved'] or 0) " +
                "local available = quantity - reserved " +
                "if available < tonumber(ARGV[1]) then " +
                "    return {0, 'INSUFFICIENT'} " +
                "end " +
                "redis.call('HINCRBY', KEYS[1], 'reserved', ARGV[1]) " +
                "redis.call('HMSET', 'reservation:' .. ARGV[2], " +
                "    'resource_id', KEYS[1], " +
                "    'quantity', ARGV[1], " +
                "    'status', 'PENDING', " +
                "    'timestamp', redis.call('TIME')[1]) " +
                "redis.call('EXPIRE', 'reservation:' .. ARGV[2], ARGV[3]) " +
                "return {1, 'SUCCESS'}";

    /**
     * Redis 연결상태 확인
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

    /**
     * 캐시 데이터 TTL - uses local cache as backup if Redis fails
     */
    public void cacheData(String key, Object value, long ttlSeconds) {
        try {
            log.debug("Caching data for key: {}", key);
            redisTemplate.opsForValue().set(key, value, ttlSeconds, TimeUnit.SECONDS);
            log.debug("Data successfully cached in Redis for key: {}", key);

            // 성공 시 로컬 백업에도 캐시
            localCache.put(key, new CacheEntry(value, System.currentTimeMillis() + (ttlSeconds * 1000)));
        } catch (Exception e) {
            log.error("Error caching data in Redis for key: {}. Using local cache. Error: {}", key, e.getMessage());
            // Redis 장애 시에만 로컬 캐시를 사용
            localCache.put(key, new CacheEntry(value, System.currentTimeMillis() + (ttlSeconds * 1000)));
        }
    }

    /**
     * TTL을 사용하여 캐시 맵 데이터
     */
    public void cacheMapData(String key, Map<String, Object> map, long ttlSeconds) {
        try {
            redisTemplate.opsForHash().putAll(key, map);
            redisTemplate.expire(key, ttlSeconds, TimeUnit.SECONDS);
            log.debug("Map data successfully cached in Redis for key: {}", key);

            // 로컬 백업에도 캐시
            localCache.put(key, new CacheEntry(map, System.currentTimeMillis() + (ttlSeconds * 1000)));
        } catch (Exception e) {
            log.error("Error caching map in Redis for key: {}. Using local cache. Error: {}", key, e.getMessage());
            localCache.put(key, new CacheEntry(map, System.currentTimeMillis() + (ttlSeconds * 1000)));
        }
    }

    /**
     * 캐시된 데이터 검색 - Redis가 실패하면 로컬 캐시로 돌아감
     */
    public Object getCachedData(String key) {
        try {
            log.debug("Retrieving data for key: {}", key);
            Object value = redisTemplate.opsForValue().get(key);

            if (value != null) {
                log.debug("Data found in Redis for key: {}", key);
                return value;
            } else {
                log.debug("Data not found in Redis for key: {}, checking local cache", key);
                CacheEntry localEntry = localCache.get(key);

                // 로컬 항목이 존재하고 만료되지 않았는지 확인
                if (localEntry != null) {
                    if (localEntry.isExpired()) {
                        localCache.remove(key);
                        return null;
                    }
                    return localEntry.getValue();
                }
                return null;
            }
        } catch (Exception e) {
            log.error("Error retrieving data from Redis for key: {}. Falling back to local cache. Error: {}",
                    key, e.getMessage());

            CacheEntry localEntry = localCache.get(key);
            if (localEntry != null && !localEntry.isExpired()) {
                return localEntry.getValue();
            }
            return null;
        }
    }

    /**
     * 키가 있는지 확인합니다. Redis가 실패하면 로컬 캐시로 돌아감
     */
    public boolean hasKey(String key) {
        try {
            boolean exists = Boolean.TRUE.equals(redisTemplate.hasKey(key));
            if (exists) {
                return true;
            } else {
                CacheEntry localEntry = localCache.get(key);
                return localEntry != null && !localEntry.isExpired();
            }
        } catch (Exception e) {
            log.error("Error checking key existence in Redis: {}. Falling back to local cache. Error: {}",
                    key, e.getMessage());

            CacheEntry localEntry = localCache.get(key);
            return localEntry != null && !localEntry.isExpired();
        }
    }

    /**
     * Redis와 로컬 캐시 모두에서 캐시된 데이터 삭제
     */
    public void deleteCache(String key) {
        try {
            redisTemplate.delete(key);
            log.debug("Data deleted from Redis for key: {}", key);
        } catch (Exception e) {
            log.error("Error deleting data from Redis for key: {}. Error: {}", key, e.getMessage());
        } finally {
            // Always remove from local cache regardless of Redis success
            localCache.remove(key);
            log.debug("Data deleted from local cache for key: {}", key);
        }
    }

    /**
     * TTL을 사용하여 리소스 예약을 생성합니다.
     * 반환: [success(1/0), message]
     */
    public Map<String, Object> createReservation(String resourceKey, String reservationId, int quantity, long ttlSeconds) {
        try {
            Object[] result = (Object[]) redisTemplate.execute(
                    RedisScript.of(RESERVE_SCRIPT, Object[].class),
                    Collections.singletonList(resourceKey),
                    String.valueOf(quantity), reservationId, String.valueOf(ttlSeconds)
            );

            boolean success = Integer.valueOf(1).equals(result[0]);
            Map<String, Object> response = new HashMap<>();
            response.put("success", success);
            response.put("message", result[1]);

            return response;
        } catch (Exception e) {
            log.error("Error creating reservation: {}", e.getMessage(), e);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "REDIS_ERROR");
            return response;
        }
    }

    /**
     * 진행중인 예약 확인
     */
    public boolean confirmReservation(String reservationId) {
        String reservationKey = "reservation:" + reservationId;
        try {
            Map<Object, Object> reservationData = redisTemplate.opsForHash().entries(reservationKey);

            if (reservationData.isEmpty()) {
                log.warn("Reservation not found: {}", reservationId);
                return false;
            }

            String status = (String) reservationData.get("status");
            if ("CONFIRMED".equals(status)) {
                log.info("Reservation already confirmed: {}", reservationId);
                return true;
            }

            if ("CANCELLED".equals(status)) {
                log.error("Cannot confirm cancelled reservation: {}", reservationId);
                return false;
            }

            // Update reservation status
            redisTemplate.opsForHash().put(reservationKey, "status", "CONFIRMED");

            // Resource key (e.g., "product:123")
            String resourceKey = (String) reservationData.get("resource_id");

            // Update reserved quantity (decrement)
            redisTemplate.opsForHash().increment(resourceKey, "reserved",
                    -Integer.parseInt(reservationData.get("quantity").toString()));

            log.info("Reservation confirmed: {}", reservationId);
            return true;

        } catch (Exception e) {
            log.error("Error confirming reservation: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 진행중이 예약 취소
     */
    public boolean cancelReservation(String reservationId) {
        String reservationKey = "reservation:" + reservationId;
        try {
            Map<Object, Object> reservationData = redisTemplate.opsForHash().entries(reservationKey);

            if (reservationData.isEmpty()) {
                log.warn("Reservation not found: {}", reservationId);
                return false;
            }

            String status = (String) reservationData.get("status");
            if ("CANCELLED".equals(status)) {
                log.info("Reservation already cancelled: {}", reservationId);
                return true;
            }

            if ("CONFIRMED".equals(status)) {
                log.error("Cannot cancel confirmed reservation: {}", reservationId);
                return false;
            }

            // Update reservation status
            redisTemplate.opsForHash().put(reservationKey, "status", "CANCELLED");

            // Resource key (e.g., "product:123")
            String resourceKey = (String) reservationData.get("resource_id");

            // Update reserved quantity (decrement)
            redisTemplate.opsForHash().increment(resourceKey, "reserved",
                    -Integer.parseInt(reservationData.get("quantity").toString()));

            log.info("Reservation cancelled: {}", reservationId);
            return true;

        } catch (Exception e) {
            log.error("Error cancelling reservation: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 로컬 캐시에 대한 만료가 있는 캐시 항목
     */
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
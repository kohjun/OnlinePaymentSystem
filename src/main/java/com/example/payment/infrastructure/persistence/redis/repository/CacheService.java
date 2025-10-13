package com.example.payment.infrastructure.persistence.redis.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    // Local fallback cache
    private final Map<String, Map<String, Object>> localMapCache = new ConcurrentHashMap<>();
    private final Map<String, String> localObjectCache = new ConcurrentHashMap<>();

    // ==================== Map 데이터 캐싱 (Hash 타입) ====================

    /**
     * Map 데이터를 Redis Hash로 캐싱
     */
    public void cacheMapData(String key, Map<String, Object> data, Duration ttl) {
        try {
            // Redis Hash 타입으로 저장
            redisTemplate.opsForHash().putAll(key, data);
            redisTemplate.expire(key, ttl);

            // Local cache에도 백업
            localMapCache.put(key, new ConcurrentHashMap<>(data));

            log.debug("Map data cached: key={}", key);
        } catch (Exception e) {
            log.error("Failed to cache map data for key: {}, using local cache only", key, e);
            localMapCache.put(key, new ConcurrentHashMap<>(data));
        }
    }

    /**
     * Redis Hash에서 Map 데이터 조회
     */
    public Map<String, Object> getCachedData(String key) {
        try {
            // Redis Hash 타입으로 조회
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);

            if (entries != null && !entries.isEmpty()) {
                Map<String, Object> result = entries.entrySet().stream()
                        .collect(Collectors.toMap(
                                e -> e.getKey().toString(),
                                Map.Entry::getValue
                        ));

                // Local cache 동기화
                localMapCache.put(key, new ConcurrentHashMap<>(result));

                log.debug("Map data retrieved from Redis: key={}", key);
                return result;
            }

            // Redis에 없으면 local cache 확인
            Map<String, Object> localData = localMapCache.get(key);
            if (localData != null) {
                log.debug("Map data retrieved from local cache: key={}", key);
                return new ConcurrentHashMap<>(localData);
            }

            return Collections.emptyMap();

        } catch (Exception e) {
            log.error("Redis error for key: {}, using local cache", key, e);
            Map<String, Object> localData = localMapCache.getOrDefault(key, Collections.emptyMap());
            return new ConcurrentHashMap<>(localData);
        }
    }

    /**
     * Redis Hash의 특정 필드 값 증가
     */
    public Long incrementHashField(String key, String field, long delta) {
        try {
            Long result = redisTemplate.opsForHash().increment(key, field, delta);

            // Local cache 동기화
            localMapCache.computeIfAbsent(key, k -> new ConcurrentHashMap<>())
                    .merge(field, result, (oldVal, newVal) -> newVal);

            return result;
        } catch (Exception e) {
            log.error("Failed to increment hash field for key: {}, field: {}", key, field, e);

            // Local cache에서 증가
            Map<String, Object> data = localMapCache.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
            Long currentValue = data.containsKey(field) ?
                    Long.parseLong(data.get(field).toString()) : 0L;
            Long newValue = currentValue + delta;
            data.put(field, newValue);

            return newValue;
        }
    }

    // ==================== 객체 캐싱 (String 타입, JSON 직렬화) ====================

    /**
     * 객체를 JSON으로 직렬화하여 Redis String으로 캐싱 (초 단위)
     */
    public void cacheData(String key, Object data, int ttlSeconds) {
        cacheObject(key, data, Duration.ofSeconds(ttlSeconds));
    }

    /**
     * 객체를 JSON으로 직렬화하여 Redis String으로 캐싱 (Duration)
     */
    public void cacheObject(String key, Object data, Duration ttl) {
        try {
            String jsonData = objectMapper.writeValueAsString(data);
            redisTemplate.opsForValue().set(key, jsonData, ttl);

            // Local cache에도 백업
            localObjectCache.put(key, jsonData);

            log.debug("Object cached: key={}", key);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize object for key: {}", key, e);
            // 직렬화 실패 시 local cache에만 저장 시도
            try {
                String jsonData = objectMapper.writeValueAsString(data);
                localObjectCache.put(key, jsonData);
            } catch (JsonProcessingException ex) {
                log.error("Failed to cache object locally for key: {}", key, ex);
            }
        } catch (Exception e) {
            log.error("Failed to cache object for key: {}", key, e);
        }
    }

    /**
     * Redis String에서 객체를 조회하여 역직렬화
     */
    public <T> T getCachedObject(String key, Class<T> type) {
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (value != null) {
                String jsonData = value.toString();
                // Local cache 동기화
                localObjectCache.put(key, jsonData);
                return objectMapper.readValue(jsonData, type);
            }

            // Redis에 없으면 local cache 확인
            String localData = localObjectCache.get(key);
            if (localData != null) {
                log.debug("Object retrieved from local cache: key={}", key);
                return objectMapper.readValue(localData, type);
            }

            return null;
        } catch (Exception e) {
            log.error("Failed to deserialize object for key: {}", key, e);

            // Local cache에서 재시도
            try {
                String localData = localObjectCache.get(key);
                if (localData != null) {
                    return objectMapper.readValue(localData, type);
                }
            } catch (Exception ex) {
                log.error("Failed to deserialize from local cache for key: {}", key, ex);
            }

            return null;
        }
    }

    // ==================== 캐시 관리 ====================

    /**
     * 캐시 삭제
     */
    public void deleteCache(String key) {
        evict(key);
    }

    /**
     * 캐시 삭제 (evict와 동일)
     */
    public void evict(String key) {
        try {
            redisTemplate.delete(key);
            localMapCache.remove(key);
            localObjectCache.remove(key);
            log.debug("Cache evicted: key={}", key);
        } catch (Exception e) {
            log.error("Failed to evict cache for key: {}", key, e);
            localMapCache.remove(key);
            localObjectCache.remove(key);
        }
    }

    /**
     * 캐시 존재 여부 확인
     */
    public boolean exists(String key) {
        try {
            Boolean exists = redisTemplate.hasKey(key);
            return exists != null && exists;
        } catch (Exception e) {
            log.error("Failed to check cache existence for key: {}", key, e);
            return localMapCache.containsKey(key) || localObjectCache.containsKey(key);
        }
    }

    /**
     * TTL 설정
     */
    public void expire(String key, Duration ttl) {
        try {
            redisTemplate.expire(key, ttl);
        } catch (Exception e) {
            log.error("Failed to set TTL for key: {}", key, e);
        }
    }

    /**
     * Redis 연결 상태 확인
     */
    public boolean isRedisConnected() {
        try {
            redisTemplate.getConnectionFactory().getConnection().ping();
            return true;
        } catch (Exception e) {
            log.warn("Redis connection check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Local cache 상태 조회 (디버깅용)
     */
    public Map<String, Object> getLocalCacheStats() {
        return Map.of(
                "mapCacheSize", localMapCache.size(),
                "objectCacheSize", localObjectCache.size(),
                "totalSize", localMapCache.size() + localObjectCache.size()
        );
    }

    /**
     * Local cache 초기화
     */
    public void clearLocalCache() {
        localMapCache.clear();
        localObjectCache.clear();
        log.info("Local cache cleared");
    }
}
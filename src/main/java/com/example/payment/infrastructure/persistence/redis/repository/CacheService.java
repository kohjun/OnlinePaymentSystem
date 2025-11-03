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
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    // [수정] 2. 로컬 캐시 필드 제거 (분산 환경에서 데이터 불일치 유발)
    // private final Map<String, Map<String, Object>> localMapCache = new ConcurrentHashMap<>();
    // private final Map<String, String> localObjectCache = new ConcurrentHashMap<>();

    // ==================== Map 데이터 캐싱 (Hash 타입) ====================

    /**
     * Map 데이터를 Redis Hash로 캐싱
     * [수정] 3. 로컬 캐시 백업 로직 제거
     */
    public void cacheMapData(String key, Map<String, Object> data, Duration ttl) {
        try {
            // Redis Hash 타입으로 저장
            redisTemplate.opsForHash().putAll(key, data);
            redisTemplate.expire(key, ttl);

            log.debug("Map data cached: key={}", key);
        } catch (Exception e) {
            // [수정] 로컬 캐시 대신 에러 로깅만 수행
            log.error("Failed to cache map data for key: {}", key, e);
        }
    }

    /**
     * Redis Hash에서 Map 데이터 조회
     * [수정] 4. 로컬 캐시 조회 로직 제거
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

                log.debug("Map data retrieved from Redis: key={}", key);
                return result;
            }

            // [수정] 로컬 캐시 확인 로직 제거
            return Collections.emptyMap();

        } catch (Exception e) {
            // [수정] 로컬 캐시 대신 에러 로깅 후 빈 맵 반환
            log.error("Redis error for key: {}, returning empty map", key, e);
            return Collections.emptyMap();
        }
    }

    /**
     * Redis Hash의 특정 필드 값 증가
     * [수정] 5. 로컬 캐시 동기화 로직 제거
     */
    /**
     * 객체를 JSON으로 직렬화하여 Redis String으로 캐싱 (초 단위)
     */
    public void cacheData(String key, Object data, int ttlSeconds) {
        cacheObject(key, data, Duration.ofSeconds(ttlSeconds));
    }

    /**
     * 객체를 JSON으로 직렬화하여 Redis String으로 캐싱 (Duration)
     * [수정] 6. 로컬 캐시 백업 로직 제거
     */
    public void cacheObject(String key, Object data, Duration ttl) {
        try {
            String jsonData = objectMapper.writeValueAsString(data);
            redisTemplate.opsForValue().set(key, jsonData, ttl);
            log.debug("Object cached: key={}", key);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize object for key: {}", key, e);
        } catch (Exception e) {
            // [수정] 로컬 캐시 대신 에러 로깅
            log.error("Failed to cache object for key: {}", key, e);
        }
    }

    /**
     * Redis String에서 객체를 조회하여 역직렬화
     * [수정] 7. 로컬 캐시 조회 로직 제거
     */
    public <T> T getCachedObject(String key, Class<T> type) {
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (value != null) {
                String jsonData = value.toString();
                return objectMapper.readValue(jsonData, type);
            }

            // [수정] 로컬 캐시 확인 로직 제거
            return null;
        } catch (Exception e) {
            // [수정] 로컬 캐시 대신 에러 로깅 후 null 반환
            log.error("Failed to deserialize object for key: {}", key, e);
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
     * [수정] 8. 로컬 캐시 제거 로직 제거
     */
    public void evict(String key) {
        try {
            redisTemplate.delete(key);
            log.debug("Cache evicted: key={}", key);
        } catch (Exception e) {
            // [수정] 로컬 캐시 대신 에러 로깅
            log.error("Failed to evict cache for key: {}", key, e);
        }
    }

    /**
     * 캐시 존재 여부 확인
     * [수정] 9. 로컬 캐시 확인 로직 제거
     */
    public boolean exists(String key) {
        try {
            Boolean exists = redisTemplate.hasKey(key);
            return exists != null && exists;
        } catch (Exception e) {
            // [수정] 로컬 캐시 대신 에러 로깅 후 false 반환
            log.error("Failed to check cache existence for key: {}", key, e);
            return false;
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


}
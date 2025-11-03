package com.example.payment.infrastructure.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceReservationService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final DefaultRedisScript<String> reserveScript;  // RedisConfig에서 주입
    private final DefaultRedisScript<String> releaseScript;  // RedisConfig에서 주입
    private final ObjectMapper objectMapper;

    /**
     * 리소스 예약 (ReservationService에서 사용)
     */
    public boolean reserveResource(String resourceKey, int quantity, Duration ttl) {
        try {
            List<String> keys = Collections.singletonList(resourceKey);
            long ttlSeconds = (ttl != null) ? ttl.getSeconds() : 0;
            String reservationId = UUID.randomUUID().toString();

            // Lua 스크립트 실행 - JSON 문자열로 받기
            String jsonResult = redisTemplate.execute(
                    reserveScript,
                    keys,
                    String.valueOf(quantity),
                    reservationId,
                    String.valueOf(ttlSeconds),
                    String.valueOf(System.currentTimeMillis())
            );

            log.debug("Reserve script result: {}", jsonResult);

            // JSON 파싱
            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(jsonResult, Map.class);

            if ("SUCCESS".equals(result.get("status"))) {
                log.info("Resource reserved: key={}, quantity={}, available={}, reserved={}",
                        resourceKey, quantity, result.get("available"), result.get("reserved"));
                return true;
            } else {
                log.warn("Reserve failed: key={}, code={}, message={}",
                        resourceKey, result.get("code"), result.get("message"));
                return false;
            }

        } catch (Exception e) {
            log.error("Error reserving resource {}: {}", resourceKey, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 리소스 해제 (취소/롤백 시 사용)
     */
    public boolean releaseResource(String resourceKey, int quantity) {
        try {
            List<String> keys = Collections.singletonList(resourceKey);
            String reservationId = "";  // 필요 시 추가

            // Lua 스크립트 실행 - JSON 문자열로 받기
            String jsonResult = redisTemplate.execute(
                    releaseScript,
                    keys,
                    String.valueOf(quantity),
                    reservationId
            );

            log.debug("Release script result: {}", jsonResult);

            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(jsonResult, Map.class);

            if ("SUCCESS".equals(result.get("status"))) {
                log.info("Resource released: key={}, quantity={}, available={}, reserved={}",
                        resourceKey, quantity, result.get("available"), result.get("reserved"));
                return true;
            } else {
                log.warn("Release failed: key={}, code={}, message={}",
                        resourceKey, result.get("code"), result.get("message"));
                return false;
            }

        } catch (Exception e) {
            log.error("Error releasing resource {}: {}", resourceKey, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 재고 초기화 (테스트용)
     */
    public void initializeResource(String resourceKey, int total, int available) {
        try {
            Map<String, Object> resourceData = new HashMap<>();
            resourceData.put("total", total);
            resourceData.put("available", available);
            resourceData.put("reserved", 0);

            redisTemplate.opsForHash().putAll(resourceKey, resourceData);
            log.info("Resource initialized: key={}, total={}, available={}",
                    resourceKey, total, available);
        } catch (Exception e) {
            log.error("Error initializing resource {}: {}", resourceKey, e.getMessage());
        }
    }

    /**
     * 재고 상태 조회
     */
    public Map<String, Object> getResourceStatus(String resourceKey) {
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(resourceKey);
            Map<String, Object> result = new HashMap<>();
            entries.forEach((k, v) -> result.put(k.toString(), v));
            return result;
        } catch (Exception e) {
            log.error("Error getting resource status {}: {}", resourceKey, e.getMessage());
            return new HashMap<>();
        }
    }
}
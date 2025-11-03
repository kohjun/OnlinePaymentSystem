package com.example.payment.infrastructure.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceReservationService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    private final DefaultRedisScript<String> reserveScript;
    private final DefaultRedisScript<String> cancelScript;
    private final DefaultRedisScript<String> confirmScript;

    /**
     * 리소스 예약 (ReservationService에서 사용)
     */
    public boolean reserveResource(String resourceKey, int quantity, Duration ttl) {
        try {
            List<String> keys = Collections.singletonList(resourceKey);
            long ttlSeconds = (ttl != null) ? ttl.getSeconds() : 0;
            String reservationId = UUID.randomUUID().toString();

            String jsonResult = redisTemplate.execute(
                    reserveScript,
                    keys,
                    String.valueOf(quantity),
                    reservationId,
                    String.valueOf(ttlSeconds),
                    String.valueOf(System.currentTimeMillis())
            );

            log.debug("Reserve script result: {}", jsonResult);

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
    public boolean releaseResource(String resourceKey, int quantity, String reservationId) {
        try {
            List<String> keys = Collections.singletonList(resourceKey);

            String jsonResult = redisTemplate.execute(
                    cancelScript,
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
     * 리소스 예약 확정
     * [수정] 1. 'reservationId' 인자 추가 (Lua 스크립트 ARGV[2] 전달용)
     */
    public boolean confirmResource(String resourceKey, int quantity, String reservationId) {
        try {
            List<String> keys = Collections.singletonList(resourceKey);

            // [수정] 2. 'reservationId'를 스크립트에 전달
            String jsonResult = redisTemplate.execute(
                    confirmScript,
                    keys,
                    String.valueOf(quantity), // ARGV[1]
                    reservationId             // ARGV[2]
            );

            log.debug("Confirm script result: {}", jsonResult);

            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(jsonResult, Map.class);

            if ("SUCCESS".equals(result.get("status"))) {
                log.info("Resource confirmed: key={}, quantity={}, available={}, reserved={}",
                        resourceKey, quantity, result.get("available"), result.get("reserved"));
                return true;
            } else {
                log.warn("Confirm failed: key={}, code={}, message={}",
                        resourceKey, result.get("code"), result.get("message"));
                return false;
            }

        } catch (Exception e) {
            log.error("Error confirming resource {}: {}", resourceKey, e.getMessage(), e);
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
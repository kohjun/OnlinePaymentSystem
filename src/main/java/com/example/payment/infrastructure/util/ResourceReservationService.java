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
     * [수정] 3. ClassCastException 해결:
     * redisTemplate이 JSON을 역직렬화하여 Map(Object)으로 반환하므로,
     * String으로 받고 다시 파싱하는 대신, 반환된 Map을 직접 사용합니다.
     */
    public boolean reserveResource(String resourceKey, int quantity, Duration ttl, String reservationId) {
        try {
            List<String> keys = Collections.singletonList(resourceKey);
            long ttlSeconds = (ttl != null) ? ttl.getSeconds() : 0;

            // [수정] String jsonResult = ... -> Object rawResult = ...
            Object rawResult = redisTemplate.execute(
                    reserveScript,
                    keys,
                    quantity,
                    reservationId,
                    ttlSeconds,
                    String.valueOf(System.currentTimeMillis())
            );

            // [수정] rawResult를 Map으로 캐스팅 (objectMapper.readValue 제거)
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) rawResult;

            log.debug("Reserve script result: {}", result);

            if (result != null && "SUCCESS".equals(result.get("status"))) {
                log.info("Resource reserved: key={}, quantity={}, available={}, reserved={}",
                        resourceKey, quantity, result.get("available"), result.get("reserved"));
                return true;
            } else {
                log.warn("Reserve failed: key={}, code={}, message={}",
                        resourceKey,
                        (result != null ? result.get("code") : "UNKNOWN"),
                        (result != null ? result.get("message") : "Null result from script"));
                return false;
            }

        } catch (Exception e) {
            log.error("Error reserving resource {}: {}", resourceKey, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 리소스 해제 (취소/롤백 시 사용)
     * [수정] 3. ClassCastException 해결
     */
    public boolean releaseResource(String resourceKey, int quantity, String reservationId) {
        try {
            List<String> keys = Collections.singletonList(resourceKey);

            // [수정] String jsonResult = ... -> Object rawResult = ...
            Object rawResult = redisTemplate.execute(
                    cancelScript,
                    keys,
                    quantity,
                    reservationId
            );

            // [수정] rawResult를 Map으로 캐스팅 (objectMapper.readValue 제거)
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) rawResult;

            log.debug("Release script result: {}", result);

            if (result != null && "SUCCESS".equals(result.get("status"))) {
                log.info("Resource released: key={}, quantity={}, available={}, reserved={}",
                        resourceKey, quantity, result.get("available"), result.get("reserved"));
                return true;
            } else {
                log.warn("Release failed: key={}, code={}, message={}",
                        resourceKey,
                        (result != null ? result.get("code") : "UNKNOWN"),
                        (result != null ? result.get("message") : "Null result from script"));
                return false;
            }

        } catch (Exception e) {
            log.error("Error releasing resource {}: {}", resourceKey, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 리소스 예약 확정
     * [수정] 3. ClassCastException 해결
     */
    public boolean confirmResource(String resourceKey, int quantity, String reservationId) {
        try {
            List<String> keys = Collections.singletonList(resourceKey);

            // [수정] String jsonResult = ... -> Object rawResult = ...
            Object rawResult = redisTemplate.execute(
                    confirmScript,
                    keys,
                    quantity,
                    reservationId
            );

            // [수정] rawResult를 Map으로 캐스팅 (objectMapper.readValue 제거)
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) rawResult;

            log.debug("Confirm script result: {}", result);

            if (result != null && "SUCCESS".equals(result.get("status"))) {
                log.info("Resource confirmed: key={}, quantity={}, available={}, reserved={}",
                        resourceKey, quantity, result.get("available"), result.get("reserved"));
                return true;
            } else {
                log.warn("Confirm failed: key={}, code={}, message={}",
                        resourceKey,
                        (result != null ? result.get("code") : "UNKNOWN"),
                        (result != null ? result.get("message") : "Null result from script"));
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
package com.example.payment.infrastructure.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
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

    private final DefaultRedisScript<Object> reserveScript;
    private final DefaultRedisScript<Object> cancelScript;
    private final DefaultRedisScript<Object> confirmScript;
    private static final TypeReference<Map<String, Object>> SCRIPT_RESULT_TYPE = new TypeReference<>() {};

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

            Object rawResult = redisTemplate.execute(
                    reserveScript,
                    keys,
                    quantity,
                    reservationId,
                    ttlSeconds,
                    String.valueOf(System.currentTimeMillis())
            );

            Map<String, Object> result = readScriptResult(rawResult);

            log.debug("Reserve script result: {}", result);

            if (result != null && "SUCCESS".equals(result.get("status"))) {
                log.info("Resource reserved: key={}, quantity={}, available={}, reserved={}",
                        resourceKey, quantity, result.get("available"), result.get("reserved"));
                return true;
            } else if (isBusinessReservationFailure(result)) {
                log.warn("Reserve failed due to insufficient stock: key={}, code={}, message={}",
                        resourceKey,
                        result.get("code"),
                        result.get("message"));
                return false;
            } else {
                log.warn("Reserve failed: key={}, code={}, message={}",
                        resourceKey,
                        (result != null ? result.get("code") : "UNKNOWN"),
                        (result != null ? result.get("message") : "Null result from script"));
                throw infrastructureFailure("Reserve script failed", resourceKey, result);
            }

        } catch (Exception e) {
            log.error("Error reserving resource {}: {}", resourceKey, e.getMessage(), e);
            if (e instanceof ResourceReservationInfrastructureException infrastructureException) {
                throw infrastructureException;
            }
            throw new ResourceReservationInfrastructureException("Redis reservation failed: " + resourceKey, e);
        }
    }

    /**
     * 리소스 해제 (취소/롤백 시 사용)
     */
    public boolean releaseResource(String resourceKey, int quantity, String reservationId) {
        try {
            List<String> keys = Collections.singletonList(resourceKey);

            Object rawResult = redisTemplate.execute(
                    cancelScript,
                    keys,
                    quantity,
                    reservationId
            );

            Map<String, Object> result = readScriptResult(rawResult);

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
                throw infrastructureFailure("Release script failed", resourceKey, result);
            }

        } catch (Exception e) {
            log.error("Error releasing resource {}: {}", resourceKey, e.getMessage(), e);
            if (e instanceof ResourceReservationInfrastructureException infrastructureException) {
                throw infrastructureException;
            }
            throw new ResourceReservationInfrastructureException("Redis reservation release failed: " + resourceKey, e);
        }
    }

    /**
     * 리소스 예약 확정
     */
    public boolean confirmResource(String resourceKey, int quantity, String reservationId) {
        try {
            List<String> keys = Collections.singletonList(resourceKey);

            Object rawResult = redisTemplate.execute(
                    confirmScript,
                    keys,
                    quantity,
                    reservationId
            );

            Map<String, Object> result = readScriptResult(rawResult);

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
                throw infrastructureFailure("Confirm script failed", resourceKey, result);
            }

        } catch (Exception e) {
            log.error("Error confirming resource {}: {}", resourceKey, e.getMessage(), e);
            if (e instanceof ResourceReservationInfrastructureException infrastructureException) {
                throw infrastructureException;
            }
            throw new ResourceReservationInfrastructureException("Redis reservation confirmation failed: " + resourceKey, e);
        }
    }


    /**
     * 재고 초기화
     */
    public void initializeResource(String resourceKey, int total, int available) {
        try {
            Map<String, Object> resourceData = new HashMap<>();
            resourceData.put("total", total);
            resourceData.put("available", available);
            resourceData.put("reserved", total - available);

            redisTemplate.opsForHash().putAll(resourceKey, resourceData);
            log.info("Resource initialized: key={}, total={}, available={}, reserved={}",
                    resourceKey, total, available, total - available);
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

    private Map<String, Object> readScriptResult(Object rawResult) throws Exception {
        if (rawResult == null) {
            return Map.of("status", "ERROR", "code", "EMPTY_RESULT", "message", "Lua script returned no data");
        }
        if (rawResult instanceof Map<?, ?> mapResult) {
            return objectMapper.convertValue(mapResult, SCRIPT_RESULT_TYPE);
        }
        if (rawResult instanceof String stringResult) {
            if (stringResult.isBlank()) {
                return Map.of("status", "ERROR", "code", "EMPTY_RESULT", "message", "Lua script returned no data");
            }
            return objectMapper.readValue(stringResult, SCRIPT_RESULT_TYPE);
        }
        return objectMapper.convertValue(rawResult, SCRIPT_RESULT_TYPE);
    }

    private boolean isBusinessReservationFailure(Map<String, Object> result) {
        return result != null && "INSUFFICIENT_STOCK".equals(String.valueOf(result.get("code")));
    }

    private ResourceReservationInfrastructureException infrastructureFailure(String message, String resourceKey,
                                                                            Map<String, Object> result) {
        String code = result != null ? String.valueOf(result.get("code")) : "UNKNOWN";
        String detail = result != null ? String.valueOf(result.get("message")) : "Null result from script";
        return new ResourceReservationInfrastructureException(
                message + ": key=" + resourceKey + ", code=" + code + ", message=" + detail
        );
    }
}

package com.example.payment.infrastructure.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceReservationService {

    private final RedisTemplate<String, Object> redisTemplate;
    @Qualifier("redisObjectMapper")
    private final ObjectMapper objectMapper;

    private final DefaultRedisScript<String> reserveScript;
    private final DefaultRedisScript<String> cancelScript;
    private final DefaultRedisScript<String> confirmScript;
    private static final DefaultRedisScript<String> RESTORE_CONFIRMED_SCRIPT = new DefaultRedisScript<>("""
            local quantity = tonumber(ARGV[1])
            local operationTtl = tonumber(ARGV[2])
            if redis.call('SETNX', KEYS[2], '1') == 0 then
              return '0'
            end
            redis.call('EXPIRE', KEYS[2], operationTtl)
            local total = tonumber(redis.call('HGET', KEYS[1], 'total'))
            local available = tonumber(redis.call('HGET', KEYS[1], 'available'))
            if total == nil or available == nil then
              redis.call('DEL', KEYS[2])
              return '-1'
            end
            local restored = math.min(total, available + quantity)
            redis.call('HSET', KEYS[1], 'available', restored)
            return tostring(restored - available)
            """, String.class);
    private static final TypeReference<Map<String, Object>> SCRIPT_RESULT_TYPE = new TypeReference<>() {};

    /**
     * Lua 스크립트 인자와 결과는 문자열로 주고받는다.
     *
     * RedisTemplate은 값 직렬화기로 스크립트 인자까지 직렬화한다. 이 템플릿의
     * 값 직렬화기는 Jackson이라 문자열 인자가 따옴표째 전달됐고, 그 결과
     * 예약 키가 inventory:reservation:"RES-x" 형태로 저장되고 있었다.
     * 숫자 인자는 따옴표가 없어 멀쩡했기 때문에 오랫동안 드러나지 않았다.
     * 스크립트는 어차피 Redis에서 모든 ARGV를 문자열로 받고 필요할 때
     * tonumber()로 바꾸므로, 처음부터 문자열로 넘기는 편이 정확하다.
     */
    private static final RedisSerializer<String> SCRIPT_SERIALIZER = RedisSerializer.string();

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
                    SCRIPT_SERIALIZER,
                    SCRIPT_SERIALIZER,
                    keys,
                    String.valueOf(quantity),
                    reservationId,
                    String.valueOf(ttlSeconds),
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
                    SCRIPT_SERIALIZER,
                    SCRIPT_SERIALIZER,
                    keys,
                    String.valueOf(quantity),
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
                    SCRIPT_SERIALIZER,
                    SCRIPT_SERIALIZER,
                    keys,
                    String.valueOf(quantity),
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

    public boolean restoreConfirmedResource(String resourceKey, int quantity, String operationKey) {
        try {
            String rawRestored = redisTemplate.execute(
                    RESTORE_CONFIRMED_SCRIPT,
                    SCRIPT_SERIALIZER,
                    SCRIPT_SERIALIZER,
                    List.of(resourceKey, "inventory:restore-operation:" + operationKey),
                    String.valueOf(quantity),
                    String.valueOf(Duration.ofDays(30).toSeconds())
            );
            long restored = rawRestored != null ? Long.parseLong(rawRestored) : -1L;
            if (restored < 0) {
                throw new ResourceReservationInfrastructureException(
                        "Redis confirmed inventory restoration failed: " + resourceKey);
            }
            return true;
        } catch (ResourceReservationInfrastructureException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ResourceReservationInfrastructureException(
                    "Redis confirmed inventory restoration failed: " + resourceKey, failure);
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
            throw new ResourceReservationInfrastructureException(
                    "Redis inventory initialization failed: " + resourceKey, e);
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
            throw new ResourceReservationInfrastructureException(
                    "Redis inventory status read failed: " + resourceKey, e);
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

package com.example.payment.service;

import java.util.Collections;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis Lua 스크립트를 사용한 리소스 예약 서비스
 * - 결제 관련 리소스 예약을 원자적으로 처리
 * - 멱등성 및 동시성 안전한 작업 보장
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ResourceReservationService {

    private final RedisTemplate<String, Object> redisTemplate;

    // 리소스 예약 스크립트 로드 - List 타입으로 수정
    private final RedisScript<List> reserveScript = new DefaultRedisScript<>(
            loadScriptFromFile("scripts/reserve_resource.lua"),
            List.class
    );

    // 예약 확정 스크립트 로드
    private final RedisScript<Boolean> confirmScript = new DefaultRedisScript<>(
            loadScriptFromFile("scripts/confirm_reservation.lua"),
            Boolean.class
    );

    // 예약 취소 스크립트 로드
    private final RedisScript<Boolean> cancelScript = new DefaultRedisScript<>(
            loadScriptFromFile("scripts/cancel_reservation.lua"),
            Boolean.class
    );

    /**
     * 리소스 예약
     * @param resourceKey 리소스 키 (예: "order:12345")
     * @param reservationId 예약 ID (멱등성 키)
     * @param quantity 예약 수량
     * @param ttlSeconds TTL (초)
     * @return [성공 여부, 메시지]
     */
    @SuppressWarnings("unchecked")
    public List<Object> reserveResource(String resourceKey, String reservationId, int quantity, int ttlSeconds) {
        try {
            // Redis Lua 스크립트 실행
            // 반환 타입을 Object로 선언하고 캐스팅
            List result = redisTemplate.execute(
                    reserveScript,
                    Collections.singletonList(resourceKey),
                    reservationId, String.valueOf(quantity), String.valueOf(ttlSeconds)
            );

            log.debug("Resource reservation result for {}: {}", resourceKey, result);
            return result;
        } catch (Exception e) {
            log.error("Error reserving resource {}: {}", resourceKey, e.getMessage(), e);
            return List.of(false, "REDIS_ERROR: " + e.getMessage());
        }
    }

    /**
     * 예약 확정
     * @param reservationId 예약 ID
     * @return 성공 여부
     */
    public boolean confirmReservation(String reservationId) {
        try {
            Boolean result = redisTemplate.execute(
                    confirmScript,
                    Collections.singletonList("reservation:" + reservationId)
            );

            log.debug("Reservation confirmation result for {}: {}", reservationId, result);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.error("Error confirming reservation {}: {}", reservationId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 예약 취소
     * @param reservationId 예약 ID
     * @return 성공 여부
     */
    public boolean cancelReservation(String reservationId) {
        try {
            Boolean result = redisTemplate.execute(
                    cancelScript,
                    Collections.singletonList("reservation:" + reservationId)
            );

            log.debug("Reservation cancellation result for {}: {}", reservationId, result);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.error("Error cancelling reservation {}: {}", reservationId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 스크립트 파일 로드
     */
    private String loadScriptFromFile(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            byte[] bytes = resource.getInputStream().readAllBytes();
            return new String(bytes);
        } catch (Exception e) {
            log.error("Error loading Lua script from {}: {}", path, e.getMessage(), e);
            throw new RuntimeException("Failed to load Lua script: " + path, e);
        }
    }
}
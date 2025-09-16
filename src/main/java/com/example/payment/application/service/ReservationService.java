/**
 * ========================================
 * 1. ReservationService (재고 선점 전담)
 * ========================================
 */
package com.example.payment.application.service;


import com.example.payment.domain.exception.ReservationException;
import com.example.payment.domain.model.reservation.InventoryReservation;
import com.example.payment.domain.model.reservation.ReservationStatus;
import com.example.payment.infrastructure.util.ResourceReservationService;
import com.example.payment.infrastructure.buffer.WriteBufferService;
import com.example.payment.infrastructure.buffer.ReservationWriteCommand;  // 누락된 import 추가
import com.example.payment.infrastructure.util.IdGenerator;
import com.example.payment.infrastructure.persistance.redis.repository.CacheService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReservationService {

    private final ResourceReservationService redisReservationService;
    private final WriteBufferService writeBufferService;
    private final CacheService cacheService;

    /**
     * 재고 선점 - 도메인 객체만 반환
     */
    public InventoryReservation reserveInventory(String productId, String customerId,
                                                 Integer quantity, String clientId) {

        log.info("Reserving inventory: productId={}, customerId={}, quantity={}",
                productId, customerId, quantity);

        try {
            String reservationId = IdGenerator.generateReservationId();
            LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(5);

            // Redis 원자적 재고 선점
            List<Object> result = redisReservationService.reserveResource(
                    "inventory:" + productId,
                    reservationId,
                    quantity,
                    300 // 5분
            );

            boolean success = (Boolean) result.get(0);

            if (!success) {
                String message = (String) result.get(1);
                log.warn("Inventory reservation failed: productId={}, reason={}", productId, message);
                return null; // 실패 시 null 반환
            }

            // 도메인 객체 생성
            InventoryReservation reservation = InventoryReservation.builder()
                    .reservationId(reservationId)
                    .productId(productId)
                    .customerId(customerId)
                    .quantity(quantity)
                    .status(ReservationStatus.valueOf("RESERVED"))
                    .createdAt(LocalDateTime.now())
                    .expiresAt(expiresAt)
                    .build();

            // Write-Through: 즉시 캐시 저장
            String cacheKey = "reservation:" + reservationId;
            cacheService.cacheData(cacheKey, reservation, 300);

            // Buffer: DB 쓰기는 비동기
            ReservationWriteCommand writeCommand = new ReservationWriteCommand(
                    reservationId, productId, customerId, quantity, "RESERVED", expiresAt
            );
            writeBufferService.enqueue(writeCommand);

            log.info("Inventory reserved successfully: reservationId={}", reservationId);
            return reservation;

        } catch (Exception e) {
            log.error("Error reserving inventory: productId={}, customerId={}", productId, customerId, e);
            throw new ReservationException("재고 선점 중 오류 발생", e);
        }
    }

    /**
     * 예약 취소
     */
    public boolean cancelReservation(String reservationId, String customerId) {
        try {
            log.info("Cancelling reservation: reservationId={}, customerId={}", reservationId, customerId);

            // Redis에서 예약 취소
            boolean cancelled = redisReservationService.cancelReservation(reservationId);

            if (cancelled) {
                // 캐시 정리
                String cacheKey = "reservation:" + reservationId;
                cacheService.deleteCache(cacheKey);

                log.info("Reservation cancelled: reservationId={}", reservationId);
                return true;
            }

            return false;

        } catch (Exception e) {
            log.error("Error cancelling reservation: reservationId={}", reservationId, e);
            return false;
        }
    }

    /**
     * 예약 조회
     */
    public InventoryReservation getReservation(String reservationId) {
        try {
            String cacheKey = "reservation:" + reservationId;
            Object cachedData = cacheService.getCachedData(cacheKey);

            if (cachedData != null) {
                return (InventoryReservation) cachedData;
            }

            return null;

        } catch (Exception e) {
            log.error("Error getting reservation: reservationId={}", reservationId, e);
            return null;
        }
    }
}
package com.example.payment.application.service;

import com.example.payment.domain.exception.ReservationException;
import com.example.payment.domain.model.reservation.InventoryReservation;
import com.example.payment.domain.model.reservation.ReservationStatus;
import com.example.payment.infrastructure.lock.DistributedLockService;
import com.example.payment.infrastructure.persistence.redis.repository.CacheService;
import com.example.payment.infrastructure.persistence.wal.WalService;
import com.example.payment.infrastructure.util.IdGenerator;
import com.example.payment.infrastructure.util.ResourceReservationService;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * ✅ 개선된 재고 예약 서비스
 * [수정] 4. getReservation의 캐시 읽기 방식을 getCachedObject로 변경
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ReservationService {

    private final DistributedLockService lockService;
    private final WalService walService;
    private final CacheService cacheService;
    private final ResourceReservationService redisReservationService;

    private static final int DEFAULT_RESERVATION_TTL_SECONDS = 300; // 5분

    @Data
    @AllArgsConstructor
    public static class ReservationResult {
        private InventoryReservation reservation;
        private String walLogId;
    }

    public ReservationResult reserveInventory(
            String transactionId,
            String productId,
            String customerId,
            Integer quantity,
            String clientId) {

        log.info("[Phase 1] Starting inventory reservation: txId={}, productId={}, customerId={}, quantity={}",
                transactionId, productId, customerId, quantity);

        String reservationId = IdGenerator.generateReservationId();
        String lockKey = "inventory:" + productId;

        return lockService.executeWithLock(lockKey, () -> {
            String walLogId = null;
            try {
                // ===================================
                // 1. WAL Phase 1 시작 로그 기록
                // ===================================
                String entityIds = buildEntityIdsJson(reservationId, null, null);
                String afterData = buildReservationJson(
                        reservationId, productId, customerId, quantity, "RESERVED"
                );

                walLogId = walService.logOperationStart(
                        transactionId,
                        "INVENTORY_RESERVE_START",
                        "reservations",
                        entityIds,
                        afterData
                );

                log.debug("WAL Phase 1 logged: txId={}, walLogId={}, reservationId={}",
                        transactionId, walLogId, reservationId);

                // ===================================
                // 2. Redis에서 재고 선점 (Lua 스크립트)
                // ===================================
                boolean success = redisReservationService.reserveResource(
                        lockKey,
                        quantity,
                        Duration.ofSeconds(DEFAULT_RESERVATION_TTL_SECONDS),
                        reservationId
                );

                if (!success) {
                    String message = "Redis 재고 선점 실패";
                    log.warn("Inventory reservation failed: txId={}, productId={}, reason={}",
                            transactionId, productId, message);

                    walService.updateLogStatus(walLogId, "FAILED", "재고 부족: " + message);

                    walService.logOperationFailure(
                            transactionId,
                            "INVENTORY_RESERVE_FAILED",
                            "reservations",
                            entityIds,
                            message
                    );

                    return null;
                }

                // ===================================
                // 3. 도메인 객체 생성
                // ===================================
                InventoryReservation reservation = InventoryReservation.builder()
                        .reservationId(reservationId)
                        .productId(productId)
                        .customerId(customerId)
                        .quantity(quantity)
                        .status(ReservationStatus.RESERVED)
                        .createdAt(LocalDateTime.now())
                        .expiresAt(LocalDateTime.now().plusMinutes(5))
                        .build();

                // ===================================
                // 4. 캐시에 저장 (메타데이터 포함)
                // ===================================
                String cacheKey = "reservation:" + reservationId;
                // cacheData는 cacheObject의 alias이며 String(JSON)으로 저장
                cacheService.cacheData(cacheKey, reservation, DEFAULT_RESERVATION_TTL_SECONDS);

                String txMappingKey = "tx_reservation:" + transactionId;
                cacheService.cacheData(txMappingKey, reservationId, DEFAULT_RESERVATION_TTL_SECONDS);

                // ===================================
                // 5. WAL Phase 1 완료 로그
                // ===================================
                walService.logOperationComplete(
                        transactionId,
                        "INVENTORY_RESERVE_COMPLETE",
                        "reservations",
                        entityIds,
                        null,
                        afterData
                );
                walService.updateLogStatus(walLogId, "COMMITTED", "예약 완료");

                log.info("[Phase 1] Inventory reservation succeeded: txId={}, reservationId={}, productId={}",
                        transactionId, reservationId, productId);

                return new ReservationResult(reservation, walLogId);

            } catch (Exception e) {
                log.error("[Phase 1] Error during inventory reservation: txId={}, productId={}, customerId={}",
                        transactionId, productId, customerId, e);

                if (walLogId != null) {
                    walService.updateLogStatus(walLogId, "FAILED", e.getMessage());
                }
                String entityIds = buildEntityIdsJson(reservationId, null, null);
                walService.logOperationFailure(
                        transactionId,
                        "INVENTORY_RESERVE_ERROR",
                        "reservations",
                        entityIds,
                        e.getMessage()
                );

                throw new ReservationException("재고 선점 실패", e);
            }
        });
    }

    /**
     * 개선: 예약 취소 - 트랜잭션 ID 주입
     */
    public boolean cancelReservation(String transactionId, String reservationId, String customerId) {
        try {
            log.info("🟠 Cancelling reservation: txId={}, reservationId={}, customerId={}",
                    transactionId, reservationId, customerId);

            InventoryReservation reservation = getReservation(reservationId);
            if (reservation == null) {
                log.warn("Reservation not found: reservationId={}", reservationId);
                return false;
            }

            if (!customerId.equals(reservation.getCustomerId()) && !"SYSTEM".equals(customerId)) {
                log.warn("Customer ID mismatch: reservationId={}, expected={}, actual={}",
                        reservationId, reservation.getCustomerId(), customerId);
                return false;
            }

            if (!reservation.canBeCancelled()) {
                log.warn("Reservation cannot be cancelled: reservationId={}, status={}",
                        reservationId, reservation.getStatus());
                return false;
            }

            String entityIds = buildEntityIdsJson(reservationId, null, null);
            String afterData = buildReservationJson(
                    reservationId,
                    reservation.getProductId(),
                    customerId,
                    reservation.getQuantity(),
                    "CANCELLED"
            );

            String walLogId = walService.logOperationStart(
                    transactionId,
                    "RESERVATION_CANCEL_START",
                    "reservations",
                    entityIds,
                    afterData
            );

            String resourceKey = "inventory:" + reservation.getProductId();
            int quantityToRelease = reservation.getQuantity();

            boolean cancelled = redisReservationService.releaseResource(resourceKey, quantityToRelease,reservation.getReservationId());

            if (cancelled) {
                reservation.setStatus(ReservationStatus.CANCELLED);

                String cacheKey = "reservation:" + reservationId;
                cacheService.cacheData(cacheKey, reservation, DEFAULT_RESERVATION_TTL_SECONDS);

                String beforeData = buildReservationJson(
                        reservationId,
                        reservation.getProductId(),
                        customerId,
                        reservation.getQuantity(),
                        "RESERVED"
                );

                walService.logOperationComplete(
                        transactionId,
                        "RESERVATION_CANCEL_COMPLETE",
                        "reservations",
                        entityIds,
                        beforeData,
                        afterData
                );
                walService.updateLogStatus(walLogId, "COMMITTED", "예약 취소 완료");

                log.info("Reservation cancelled: txId={}, reservationId={}",
                        transactionId, reservationId);
                return true;

            } else {
                walService.updateLogStatus(walLogId, "FAILED", "Redis 취소 실패");

                walService.logOperationFailure(
                        transactionId,
                        "RESERVATION_CANCEL_FAILED",
                        "reservations",
                        entityIds,
                        "Redis 취소 실패"
                );

                log.warn("Failed to cancel reservation in Redis: reservationId={}", reservationId);
                return false;
            }

        } catch (Exception e) {
            log.error("Error cancelling reservation: txId={}, reservationId={}",
                    transactionId, reservationId, e);

            String entityIds = buildEntityIdsJson(reservationId, null, null);
            walService.logOperationFailure(
                    transactionId,
                    "RESERVATION_CANCEL_ERROR",
                    "reservations",
                    entityIds,
                    e.getMessage()
            );

            return false;
        }
    }

    /**
     * 예약 조회
     * [수정] 4. getCachedData (Hash 읽기) -> getCachedObject (String 읽기) 변경
     */
    public InventoryReservation getReservation(String reservationId) {
        try {
            String cacheKey = "reservation:" + reservationId;
            // [수정] String(JSON)으로 저장된 객체를 읽어옵니다.
            InventoryReservation cachedData = cacheService.getCachedObject(cacheKey, InventoryReservation.class);

            if (cachedData != null) {
                log.debug("Reservation found in cache: reservationId={}", reservationId);
                return cachedData;
            }

            log.debug("Reservation not found: reservationId={}", reservationId);
            return null;

        } catch (Exception e) {
            log.error("Error getting reservation: reservationId={}", reservationId, e);
            return null;
        }
    }

    /**
     * 예약 상태를 'CONFIRMED'로 업데이트하고 캐시를 갱신
     */
    public void confirmReservationStatus(InventoryReservation reservation) {
        try {
            if (reservation == null) {
                log.warn("Cannot confirm status for null reservation.");
                return;
            }

            reservation.setStatus(ReservationStatus.CONFIRMED);

            String cacheKey = "reservation:" + reservation.getReservationId();
            cacheService.cacheData(cacheKey, reservation, DEFAULT_RESERVATION_TTL_SECONDS);

            log.info("Reservation status set to CONFIRMED and re-cached: {}", reservation.getReservationId());

        } catch (Exception e) {
            log.error("Error updating reservation status to CONFIRMED: {}", reservation.getReservationId(), e);
        }
    }

    private String buildEntityIdsJson(String reservationId, String orderId, String paymentId) {
        return String.format(
                "{\"reservationId\":\"%s\",\"orderId\":\"%s\",\"paymentId\":\"%s\"}",
                reservationId != null ? reservationId : "null",
                orderId != null ? orderId : "null",
                paymentId != null ? paymentId : "null"
        );
    }

    private String buildReservationJson(String reservationId, String productId,
                                        String customerId, Integer quantity, String status) {
        return String.format(
                "{\"reservationId\":\"%s\",\"productId\":\"%s\",\"customerId\":\"%s\"," +
                        "\"quantity\":%d,\"status\":\"%s\",\"timestamp\":\"%s\"}",
                reservationId, productId, customerId, quantity, status, LocalDateTime.now()
        );
    }
}
package com.example.payment.application.service;

import com.example.payment.domain.exception.ReservationException;
import com.example.payment.domain.model.reservation.InventoryReservation;
import com.example.payment.domain.model.reservation.ReservationStatus;
import com.example.payment.infrastructure.lock.DistributedLockService;
import com.example.payment.infrastructure.persistence.redis.repository.CacheService;
import com.example.payment.infrastructure.persistence.wal.WalService;
import com.example.payment.infrastructure.util.IdGenerator;
import com.example.payment.infrastructure.util.ResourceReservationService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ✅ 개선된 재고 예약 서비스
 *
 * 주요 개선사항:
 * 1. 트랜잭션 ID를 외부에서 주입받아 WAL 일관성 보장
 * 2. 엔티티 ID(reservationId) 추적 강화
 * 3. WAL 로그에 엔티티 메타데이터 포함
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

    /**
     * ✅ 개선: 재고 선점 (Phase 1) - 트랜잭션 ID 주입
     *
     * @param transactionId 비즈니스 트랜잭션 ID (correlationId)
     * @param productId 상품 ID
     * @param customerId 고객 ID
     * @param quantity 수량
     * @param clientId 클라이언트 ID
     * @return 예약 도메인 객체 (실패 시 null)
     */
    public InventoryReservation reserveInventory(
            String transactionId,  // ✅ 트랜잭션 ID 추가
            String productId,
            String customerId,
            Integer quantity,
            String clientId) {

        log.info("🔵 [Phase 1] Starting inventory reservation: txId={}, productId={}, customerId={}, quantity={}",
                transactionId, productId, customerId, quantity);

        String reservationId = IdGenerator.generateReservationId();
        String lockKey = "inventory:" + productId;

        // 분산 락으로 동시성 제어
        return lockService.executeWithLock(lockKey, () -> {
            try {
                // ===================================
                // 1. WAL Phase 1 시작 로그 기록
                // ===================================
                String entityIds = buildEntityIdsJson(reservationId, null, null);
                String afterData = buildReservationJson(
                        reservationId, productId, customerId, quantity, "RESERVED"
                );

                String walLogId = walService.logOperationStart(
                        transactionId,  // ✅ 트랜잭션 ID 전달
                        "INVENTORY_RESERVE_START",
                        "reservations",
                        entityIds,
                        afterData
                );

                log.debug("✅ WAL Phase 1 logged: txId={}, walLogId={}, reservationId={}",
                        transactionId, walLogId, reservationId);

                // ===================================
                // 2. Redis에서 재고 선점 (Lua 스크립트)
                // ===================================
                List<Object> redisResult = redisReservationService.reserveResource(
                        lockKey,
                        reservationId,
                        quantity,
                        DEFAULT_RESERVATION_TTL_SECONDS
                );

                boolean success = (Boolean) redisResult.get(0);
                String message = (String) redisResult.get(1);

                if (!success) {
                    log.warn("❌ Inventory reservation failed: txId={}, productId={}, reason={}",
                            transactionId, productId, message);

                    // WAL 실패 로그
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
                cacheService.cacheData(cacheKey, reservation, DEFAULT_RESERVATION_TTL_SECONDS);

                // 트랜잭션 ID 매핑 캐시 (복구 시 활용)
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

                log.info("✅ [Phase 1] Inventory reservation succeeded: txId={}, reservationId={}, productId={}",
                        transactionId, reservationId, productId);

                return reservation;

            } catch (Exception e) {
                log.error("❌ [Phase 1] Error during inventory reservation: txId={}, productId={}, customerId={}",
                        transactionId, productId, customerId, e);

                // WAL 실패 로그
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
     * ✅ 개선: 예약 취소 - 트랜잭션 ID 주입
     */
    public boolean cancelReservation(String transactionId, String reservationId, String customerId) {
        try {
            log.info("🟠 Cancelling reservation: txId={}, reservationId={}, customerId={}",
                    transactionId, reservationId, customerId);

            // 1. 예약 조회 (권한 확인)
            InventoryReservation reservation = getReservation(reservationId);
            if (reservation == null) {
                log.warn("Reservation not found: reservationId={}", reservationId);
                return false;
            }

            // 2. 권한 확인
            if (!customerId.equals(reservation.getCustomerId()) && !"SYSTEM".equals(customerId)) {
                log.warn("Customer ID mismatch: reservationId={}, expected={}, actual={}",
                        reservationId, reservation.getCustomerId(), customerId);
                return false;
            }

            // 3. 취소 가능 여부 확인
            if (!reservation.canBeCancelled()) {
                log.warn("Reservation cannot be cancelled: reservationId={}, status={}",
                        reservationId, reservation.getStatus());
                return false;
            }

            // 4. WAL 로그
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

            // 5. Redis에서 예약 취소
            boolean cancelled = redisReservationService.cancelReservation(reservationId);

            if (cancelled) {
                // 6. 도메인 상태 업데이트
                reservation.setStatus(ReservationStatus.CANCELLED);

                // 7. 캐시 업데이트
                String cacheKey = "reservation:" + reservationId;
                cacheService.cacheData(cacheKey, reservation, DEFAULT_RESERVATION_TTL_SECONDS);

                // 8. WAL 완료
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

                log.info("✅ Reservation cancelled: txId={}, reservationId={}",
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
            log.error("❌ Error cancelling reservation: txId={}, reservationId={}",
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
     */
    public InventoryReservation getReservation(String reservationId) {
        try {
            String cacheKey = "reservation:" + reservationId;
            Object cachedData = cacheService.getCachedData(cacheKey);

            if (cachedData != null) {
                log.debug("Reservation found in cache: reservationId={}", reservationId);

                if (cachedData instanceof InventoryReservation) {
                    return (InventoryReservation) cachedData;
                } else {
                    log.warn("Cached data is not InventoryReservation type: reservationId={}, actualType={}",
                            reservationId, cachedData.getClass().getName());
                    cacheService.deleteCache(cacheKey);
                }
            }

            log.debug("Reservation not found: reservationId={}", reservationId);
            return null;

        } catch (Exception e) {
            log.error("Error getting reservation: reservationId={}", reservationId, e);
            return null;
        }
    }

    // ===================================
    // Helper Methods - 엔티티 ID 추적용 JSON 빌더
    // ===================================

    /**
     * ✅ 엔티티 ID들을 JSON 형태로 구성
     * WAL 로그의 beforeData 필드에 저장하여 데이터 추적 가능
     */
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
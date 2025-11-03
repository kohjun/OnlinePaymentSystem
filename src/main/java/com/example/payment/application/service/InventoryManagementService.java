package com.example.payment.application.service;

import com.example.payment.domain.model.inventory.InventoryConfirmation;
import com.example.payment.application.service.ReservationService;
import com.example.payment.domain.model.reservation.InventoryReservation;
import com.example.payment.infrastructure.persistence.wal.WalService;
import com.example.payment.infrastructure.util.ResourceReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * [수정] 1. confirmResource 호출 시 reservationId 인자 전달
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class InventoryManagementService {

    private final WalService walService;
    private final ReservationService reservationService;
    private final ResourceReservationService redisReservationService;

    /**
     * 예약 확정 (Phase 2) - Phase 1 로그와 연결
     */
    public InventoryConfirmation confirmReservation(
            String transactionId,
            String phase1LogId,
            String reservationId,
            String orderId,
            String paymentId) {

        log.info("🟢 [Phase 2] Confirming inventory reservation: txId={}, reservationId={}, orderId={}, paymentId={}, phase1LogId={}",
                transactionId, reservationId, orderId, paymentId, phase1LogId);

        InventoryReservation reservation = reservationService.getReservation(reservationId);
        if (reservation == null) {
            log.error("[Phase 2] Reservation not found, cannot confirm: txId={}, reservationId={}",
                    transactionId, reservationId);
            return InventoryConfirmation.failure(
                    reservationId, orderId, paymentId, "예약 정보를 찾을 수 없어 확정 실패"
            );
        }

        try {
            // ===================================
            // 1. WAL Phase 2 시작 로그
            // ===================================
            String entityIds = buildEntityIdsJson(reservationId, orderId, paymentId);
            String beforeData = buildConfirmationJson(reservationId, "RESERVED");
            String afterData = buildConfirmationJson(reservationId, "CONFIRMED");

            String walLogId = walService.logPhase2Start(
                    transactionId, phase1LogId, "INVENTORY_CONFIRM_START",
                    "inventory", entityIds, beforeData, afterData
            );

            log.debug("WAL Phase 2 logged: txId={}, walLogId={}, phase1LogId={}",
                    transactionId, walLogId, phase1LogId);

            // ===================================
            // 2. Redis에서 재고 확정 (Lua: confirm_reservation.lua)
            // ===================================
            String resourceKey = "inventory:" + reservation.getProductId();
            int quantityToConfirm = reservation.getQuantity();

            // [수정] 2. confirmResource 호출 시 reservationId 전달
            boolean redisConfirmed = redisReservationService.confirmResource(
                    resourceKey,
                    quantityToConfirm,
                    reservationId // <-- [수정] 이 인자가 누락되었었음
            );

            if (!redisConfirmed) {
                log.error("Redis reservation confirmation failed: txId={}, reservationId={}",
                        transactionId, reservationId);
                walService.updateLogStatus(walLogId, "FAILED", "Redis 예약 확정 실패");
                walService.logOperationFailure(
                        transactionId, "INVENTORY_CONFIRM_FAILED", "inventory",
                        entityIds, "Redis 예약 확정 실패"
                );
                return InventoryConfirmation.failure(
                        reservationId, orderId, paymentId, "Redis 예약 확정 실패"
                );
            }

            // ===================================
            // 3. 예약 객체 상태 업데이트 (Cache)
            // ===================================
            reservationService.confirmReservationStatus(reservation);

            // ===================================
            // 4. WAL Phase 2 완료 로그
            // ===================================
            walService.logOperationComplete(
                    transactionId, "INVENTORY_CONFIRM_COMPLETE", "inventory",
                    entityIds, beforeData, afterData
            );
            walService.updateLogStatus(walLogId, "COMMITTED", "재고 확정 완료");

            log.info("[Phase 2] Inventory reservation confirmed: txId={}, reservationId={}",
                    transactionId, reservationId);

            // ===================================
            // 5. 확정 결과 도메인 객체 반환
            // ===================================
            return InventoryConfirmation.success(
                    reservationId, orderId, paymentId,
                    "재고 확정 완료", LocalDateTime.now()
            );

        } catch (Exception e) {
            log.error("[Phase 2] Error confirming inventory reservation: txId={}, reservationId={}",
                    transactionId, reservationId, e);
            String entityIds = buildEntityIdsJson(reservationId, orderId, paymentId);
            walService.logOperationFailure(
                    transactionId, "INVENTORY_CONFIRM_ERROR", "inventory",
                    entityIds, e.getMessage()
            );
            return InventoryConfirmation.failure(
                    reservationId, orderId, paymentId, "시스템 오류: " + e.getMessage()
            );
        }
    }

    /**
     * 예약 복구 (보상 트랜잭션)
     */
    public boolean rollbackReservation(
            String transactionId,
            String reservationId,
            String orderId,
            String reason) {
        try {
            log.info("[Compensation] Rolling back inventory reservation: txId={}, reservationId={}, orderId={}, reason={}",
                    transactionId, reservationId, orderId, reason);

            InventoryReservation reservation = reservationService.getReservation(reservationId);
            if (reservation == null) {
                log.warn("[Compensation] Reservation not found, rollback skipped (already cancelled or expired?): reservationId={}", reservationId);
                return true;
            }

            // 1. WAL 로그
            String entityIds = buildEntityIdsJson(reservationId, orderId, null);
            String afterData = buildConfirmationJson(reservationId, "ROLLED_BACK");
            String walLogId = walService.logOperationStart(
                    transactionId, "INVENTORY_ROLLBACK_START", "inventory",
                    entityIds, afterData
            );

            // 2. Redis에서 예약 취소
            String resourceKey = "inventory:" + reservation.getProductId();
            int quantityToRelease = reservation.getQuantity();

            boolean cancelled = redisReservationService.releaseResource(
                    resourceKey,
                    quantityToRelease,
                    reservationId
            );

            if (cancelled) {
                // 3. WAL 완료
                String beforeData = buildConfirmationJson(reservationId, "CONFIRMED"); // 또는 RESERVED
                walService.logOperationComplete(
                        transactionId, "INVENTORY_ROLLBACK_COMPLETE", "inventory",
                        entityIds, beforeData, afterData
                );
                walService.updateLogStatus(walLogId, "COMMITTED", "재고 롤백 완료: " + reason);

                log.info("[Compensation] Inventory reservation rolled back: txId={}, reservationId={}",
                        transactionId, reservationId);
                return true;

            } else {
                walService.updateLogStatus(walLogId, "FAILED", "Redis 롤백 실패");
                walService.logOperationFailure(
                        transactionId, "INVENTORY_ROLLBACK_FAILED", "inventory",
                        entityIds, "Redis 롤백 실패"
                );
                log.warn("Failed to cancel reservation in Redis: reservationId={}", reservationId);
                return false;
            }

        } catch (Exception e) {
            log.error("[Compensation] Error rolling back inventory reservation: txId={}, reservationId={}",
                    transactionId, reservationId, e);
            String entityIds = buildEntityIdsJson(reservationId, orderId, null);
            walService.logOperationFailure(
                    transactionId, "INVENTORY_ROLLBACK_ERROR", "inventory",
                    entityIds, e.getMessage()
            );
            return false;
        }
    }

    // ===================================
    // Helper Methods
    // ===================================
    private String buildEntityIdsJson(String reservationId, String orderId, String paymentId) {
        return String.format(
                "{\"reservationId\":\"%s\",\"orderId\":\"%s\",\"paymentId\":\"%s\"}",
                reservationId != null ? reservationId : "null",
                orderId != null ? orderId : "null",
                paymentId != null ? paymentId : "null"
        );
    }

    private String buildConfirmationJson(String reservationId, String status) {
        return String.format(
                "{\"reservationId\":\"%s\",\"status\":\"%s\",\"confirmedAt\":\"%s\"}",
                reservationId, status, LocalDateTime.now()
        );
    }
}
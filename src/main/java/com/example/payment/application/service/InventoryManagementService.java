package com.example.payment.application.service;

import com.example.payment.domain.model.inventory.InventoryConfirmation;
import com.example.payment.application.service.ReservationService;
import com.example.payment.domain.model.reservation.InventoryReservation;
import com.example.payment.infrastructure.util.ResourceReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * [수정] WAL 의존성 제거 및 confirmResource 호출 시 reservationId 인자 전달
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class InventoryManagementService {

    private final ReservationService reservationService;
    private final ResourceReservationService redisReservationService;

    /**
     * 예약 확정 (Phase 2)
     */
    public InventoryConfirmation confirmReservation(
            String transactionId,
            String reservationId,
            String orderId,
            String paymentId) {

        log.info("🟢 [Phase 2] Confirming inventory reservation: txId={}, reservationId={}, orderId={}, paymentId={}",
                transactionId, reservationId, orderId, paymentId);

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
            // Redis에서 재고 확정 (Lua: confirm_reservation.lua)
            // ===================================
            String resourceKey = "inventory:" + reservation.getProductId();
            int quantityToConfirm = reservation.getQuantity();

            boolean redisConfirmed = redisReservationService.confirmResource(
                    resourceKey,
                    quantityToConfirm,
                    reservationId
            );

            if (!redisConfirmed) {
                log.error("Redis reservation confirmation failed: txId={}, reservationId={}",
                        transactionId, reservationId);
                return InventoryConfirmation.failure(
                        reservationId, orderId, paymentId, "Redis 예약 확정 실패"
                );
            }

            // ===================================
            // 예약 객체 상태 업데이트 (Cache)
            // ===================================
            reservationService.confirmReservationStatus(reservation);

            log.info("[Phase 2] Inventory reservation confirmed: txId={}, reservationId={}",
                    transactionId, reservationId);

            // ===================================
            // 확정 결과 도메인 객체 반환
            // ===================================
            return InventoryConfirmation.success(
                    reservationId, orderId, paymentId,
                    "재고 확정 완료", LocalDateTime.now()
            );

        } catch (Exception e) {
            log.error("[Phase 2] Error confirming inventory reservation: txId={}, reservationId={}",
                    transactionId, reservationId, e);
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

            // Redis에서 예약 취소
            String resourceKey = "inventory:" + reservation.getProductId();
            int quantityToRelease = reservation.getQuantity();

            boolean cancelled = redisReservationService.releaseResource(
                    resourceKey,
                    quantityToRelease,
                    reservationId
            );

            if (cancelled) {
                log.info("[Compensation] Inventory reservation rolled back: txId={}, reservationId={}",
                        transactionId, reservationId);
                return true;

            } else {
                log.warn("Failed to cancel reservation in Redis: reservationId={}", reservationId);
                return false;
            }

        } catch (Exception e) {
            log.error("[Compensation] Error rolling back inventory reservation: txId={}, reservationId={}",
                    transactionId, reservationId, e);
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
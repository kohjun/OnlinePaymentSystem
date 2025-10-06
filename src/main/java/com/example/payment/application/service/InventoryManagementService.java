package com.example.payment.application.service;

import com.example.payment.domain.model.inventory.InventoryConfirmation;
import com.example.payment.infrastructure.persistence.wal.WalService;
import com.example.payment.infrastructure.util.ResourceReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 재고 관리 서비스 - 단일 책임 원칙 준수
 *
 * 🎯 단일 책임: 재고 확정(Confirmation)만 담당
 *
 * 담당 범위:
 * - 예약된 재고를 최종 확정 (Phase 2)
 * - 재고 차감 완료 처리
 *
 * 담당하지 않음:
 * - 재고 선점 → ReservationService
 * - 주문 생성 → OrderService
 * - 결제 처리 → PaymentProcessingService
 * - WAL 로그 → WalService (횡단 관심사)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class InventoryManagementService {

    // 인프라 서비스들
    private final WalService walService;
    private final ResourceReservationService redisReservationService;

    /**
     * 예약 확정 (Phase 2) - 최종 재고 차감
     *
     * 프로세스:
     * 1. WAL Phase 2 시작 로그
     * 2. Redis에서 예약 확정 (reserved -> confirmed)
     * 3. WAL 완료 로그
     *
     * @param reservationId 예약 ID
     * @param orderId 주문 ID
     * @param paymentId 결제 ID
     * @return 확정 결과 도메인 객체
     */
    public InventoryConfirmation confirmReservation(String reservationId, String orderId, String paymentId) {

        log.info("Confirming inventory reservation: reservationId={}, orderId={}, paymentId={}",
                reservationId, orderId, paymentId);

        try {
            // 1. WAL Phase 2 시작 로그
            String walLogId = walService.logPhase2Start(
                    "INVENTORY_CONFIRM_START",
                    null,
                    buildConfirmationJson(reservationId, "RESERVED"),
                    buildConfirmationJson(reservationId, "CONFIRMED")
            );

            // 2. Redis에서 예약 확정
            boolean redisConfirmed = redisReservationService.confirmReservation(reservationId);

            if (!redisConfirmed) {
                log.error("Redis reservation confirmation failed: reservationId={}", reservationId);

                // WAL 실패 로그
                walService.updateLogStatus(walLogId, "FAILED", "Redis 예약 확정 실패");

                return InventoryConfirmation.failure(
                        reservationId,
                        orderId,
                        paymentId,
                        "Redis 예약 확정 실패"
                );
            }

            // 3. WAL 완료 로그
            walService.logOperationComplete(
                    "INVENTORY_CONFIRM_COMPLETE",
                    "inventory",
                    buildConfirmationJson(reservationId, "RESERVED"),
                    buildConfirmationJson(reservationId, "CONFIRMED")
            );
            walService.updateLogStatus(walLogId, "COMMITTED", "재고 확정 완료");

            log.info("Inventory reservation confirmed: reservationId={}", reservationId);

            // 4. 성공 결과 반환
            return InventoryConfirmation.success(
                    reservationId,
                    orderId,
                    paymentId,
                    null, // productId는 필요시 추가
                    null  // quantity는 필요시 추가
            );

        } catch (Exception e) {
            log.error("Error confirming reservation: reservationId={}", reservationId, e);

            // WAL 실패 로그
            walService.logOperationFailure(
                    "INVENTORY_CONFIRM_ERROR",
                    "inventory",
                    e.getMessage()
            );

            return InventoryConfirmation.failure(
                    reservationId,
                    orderId,
                    paymentId,
                    "확정 처리 중 오류: " + e.getMessage()
            );
        }
    }

    /**
     * 재고 복원 (보상 트랜잭션)
     *
     * @param reservationId 예약 ID
     * @return 복원 성공 여부
     */
    public boolean restoreInventory(String reservationId) {
        log.info("Restoring inventory: reservationId={}", reservationId);

        try {
            // WAL 로그
            String walLogId = walService.logOperationStart(
                    "INVENTORY_RESTORE_START",
                    "inventory",
                    buildRestoreJson(reservationId)
            );

            // Redis에서 예약 취소 (재고 복원)
            boolean restored = redisReservationService.cancelReservation(reservationId);

            if (restored) {
                walService.updateLogStatus(walLogId, "COMMITTED", "재고 복원 완료");
                log.info("Inventory restored: reservationId={}", reservationId);
                return true;
            } else {
                walService.updateLogStatus(walLogId, "FAILED", "재고 복원 실패");
                log.warn("Failed to restore inventory: reservationId={}", reservationId);
                return false;
            }

        } catch (Exception e) {
            log.error("Error restoring inventory: reservationId={}", reservationId, e);

            walService.logOperationFailure(
                    "INVENTORY_RESTORE_ERROR",
                    "inventory",
                    e.getMessage()
            );

            return false;
        }
    }

    // ========================================
    // 내부 헬퍼 메서드
    // ========================================

    /**
     * 확정 JSON 생성
     */
    private String buildConfirmationJson(String reservationId, String status) {
        return String.format(
                "{\"reservationId\":\"%s\",\"status\":\"%s\",\"timestamp\":\"%s\"}",
                reservationId, status, LocalDateTime.now()
        );
    }

    /**
     * 복원 JSON 생성
     */
    private String buildRestoreJson(String reservationId) {
        return String.format(
                "{\"reservationId\":\"%s\",\"action\":\"RESTORE\",\"timestamp\":\"%s\"}",
                reservationId, LocalDateTime.now()
        );
    }
}
package com.example.payment.application.service;

import com.example.payment.domain.model.inventory.InventoryConfirmation;
import com.example.payment.infrastructure.persistence.wal.WalService;
import com.example.payment.infrastructure.util.ResourceReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * ✅ 개선된 재고 관리 서비스 - 트랜잭션 ID 정합성 강화
 *
 * 주요 개선사항:
 * 1. 트랜잭션 ID를 외부에서 주입받아 WAL 일관성 보장
 * 2. Phase 1 WAL 로그 ID를 받아서 Phase 2와 명확하게 연결
 * 3. 엔티티 ID(reservationId, orderId, paymentId) 추적 강화
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class InventoryManagementService {

    private final WalService walService;
    private final ResourceReservationService redisReservationService;

    /**
     * ✅ 개선: 예약 확정 (Phase 2) - Phase 1 로그와 연결
     *
     * @param transactionId 비즈니스 트랜잭션 ID
     * @param phase1LogId Phase 1의 WAL Entry ID (연결용)
     * @param reservationId 예약 ID
     * @param orderId 주문 ID
     * @param paymentId 결제 ID
     * @return 확정 결과 도메인 객체
     */
    public InventoryConfirmation confirmReservation(
            String transactionId,   // ✅ 트랜잭션 ID 추가
            String phase1LogId,     // ✅ Phase 1 로그 ID 추가
            String reservationId,
            String orderId,
            String paymentId) {

        log.info("🟢 [Phase 2] Confirming inventory reservation: txId={}, reservationId={}, orderId={}, paymentId={}, phase1LogId={}",
                transactionId, reservationId, orderId, paymentId, phase1LogId);

        try {
            // ===================================
            // 1. WAL Phase 2 시작 로그 (Phase 1과 연결)
            // ===================================
            String entityIds = buildEntityIdsJson(reservationId, orderId, paymentId);
            String beforeData = buildConfirmationJson(reservationId, "RESERVED");
            String afterData = buildConfirmationJson(reservationId, "CONFIRMED");

            String walLogId = walService.logPhase2Start(
                    transactionId,      // ✅ 동일한 트랜잭션 ID
                    phase1LogId,        // ✅ Phase 1 로그와 연결
                    "INVENTORY_CONFIRM_START",
                    "inventory",
                    entityIds,
                    beforeData,
                    afterData
            );

            log.debug("✅ WAL Phase 2 logged: txId={}, walLogId={}, phase1LogId={}",
                    transactionId, walLogId, phase1LogId);

            // ===================================
            // 2. Redis에서 예약 확정 (reserved -> confirmed)
            // ===================================
            boolean redisConfirmed = redisReservationService.confirmReservation(reservationId);

            if (!redisConfirmed) {
                log.error("❌ Redis reservation confirmation failed: txId={}, reservationId={}",
                        transactionId, reservationId);

                // WAL 실패 로그
                walService.updateLogStatus(walLogId, "FAILED", "Redis 예약 확정 실패");

                walService.logOperationFailure(
                        transactionId,
                        "INVENTORY_CONFIRM_FAILED",
                        "inventory",
                        entityIds,
                        "Redis 예약 확정 실패"
                );

                return InventoryConfirmation.failure(
                        reservationId,
                        orderId,
                        paymentId,
                        "Redis 예약 확정 실패"
                );
            }

            // ===================================
            // 3. WAL Phase 2 완료 로그
            // ===================================
            walService.logOperationComplete(
                    transactionId,
                    "INVENTORY_CONFIRM_COMPLETE",
                    "inventory",
                    entityIds,
                    beforeData,
                    afterData
            );
            walService.updateLogStatus(walLogId, "COMMITTED", "재고 확정 완료");

            log.info("✅ [Phase 2] Inventory reservation confirmed: txId={}, reservationId={}",
                    transactionId, reservationId);

            // ===================================
            // 4. 확정 결과 도메인 객체 반환
            // ===================================
            return InventoryConfirmation.success(
                    reservationId,
                    orderId,
                    paymentId,
                    "재고 확정 완료",
                    LocalDateTime.now()
            );

        } catch (Exception e) {
            log.error("❌ [Phase 2] Error confirming inventory reservation: txId={}, reservationId={}",
                    transactionId, reservationId, e);

            String entityIds = buildEntityIdsJson(reservationId, orderId, paymentId);
            walService.logOperationFailure(
                    transactionId,
                    "INVENTORY_CONFIRM_ERROR",
                    "inventory",
                    entityIds,
                    e.getMessage()
            );

            return InventoryConfirmation.failure(
                    reservationId,
                    orderId,
                    paymentId,
                    "시스템 오류: " + e.getMessage()
            );
        }
    }

    /**
     * ✅ 개선: 예약 복구 (보상 트랜잭션) - 트랜잭션 ID 주입
     */
    public boolean rollbackReservation(
            String transactionId,
            String reservationId,
            String orderId,
            String reason) {
        try {
            log.info("🟠 [Compensation] Rolling back inventory reservation: txId={}, reservationId={}, orderId={}, reason={}",
                    transactionId, reservationId, orderId, reason);

            // 1. WAL 로그
            String entityIds = buildEntityIdsJson(reservationId, orderId, null);
            String afterData = buildConfirmationJson(reservationId, "ROLLED_BACK");

            String walLogId = walService.logOperationStart(
                    transactionId,
                    "INVENTORY_ROLLBACK_START",
                    "inventory",
                    entityIds,
                    afterData
            );

            // 2. Redis에서 예약 취소
            boolean cancelled = redisReservationService.cancelReservation(reservationId);

            if (cancelled) {
                // 3. WAL 완료
                String beforeData = buildConfirmationJson(reservationId, "CONFIRMED");

                walService.logOperationComplete(
                        transactionId,
                        "INVENTORY_ROLLBACK_COMPLETE",
                        "inventory",
                        entityIds,
                        beforeData,
                        afterData
                );
                walService.updateLogStatus(walLogId, "COMMITTED", "재고 롤백 완료: " + reason);

                log.info("✅ [Compensation] Inventory reservation rolled back: txId={}, reservationId={}",
                        transactionId, reservationId);
                return true;

            } else {
                walService.updateLogStatus(walLogId, "FAILED", "Redis 롤백 실패");

                walService.logOperationFailure(
                        transactionId,
                        "INVENTORY_ROLLBACK_FAILED",
                        "inventory",
                        entityIds,
                        "Redis 롤백 실패"
                );

                log.warn("Failed to rollback inventory reservation in Redis: reservationId={}", reservationId);
                return false;
            }

        } catch (Exception e) {
            log.error("❌ [Compensation] Error rolling back inventory reservation: txId={}, reservationId={}",
                    transactionId, reservationId, e);

            String entityIds = buildEntityIdsJson(reservationId, orderId, null);
            walService.logOperationFailure(
                    transactionId,
                    "INVENTORY_ROLLBACK_ERROR",
                    "inventory",
                    entityIds,
                    e.getMessage()
            );

            return false;
        }
    }

    // ===================================
    // Helper Methods - 엔티티 ID 추적용 JSON 빌더
    // ===================================

    /**
     * ✅ 엔티티 ID들을 JSON 형태로 구성
     */
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
package com.example.payment.application.service;

import com.example.payment.domain.entity.WalLogEntry;
import com.example.payment.domain.model.inventory.InventoryConfirmation; // 1. 임포트 추가
import com.example.payment.domain.model.payment.Payment;
import com.example.payment.infrastructure.persistence.wal.WalLogRepository;
import com.example.payment.infrastructure.persistence.wal.WalService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * WAL (Write-Ahead Logging) 복구 서비스
 * - [수정] 'log' 변수명 충돌 해결
 * - [수정] confirmReservation 반환 타입(boolean) 불일치 해결
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WalRecoveryService {

    private final WalLogRepository walLogRepository;
    private final WalService walService;
    private final PaymentProcessingService paymentService;
    private final InventoryManagementService inventoryService;
    private final OrderService orderService;
    private final ReservationService reservationService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public void recoverPendingTransactions() {
        List<WalLogEntry> pendingLogs = walLogRepository.findPendingTransactions();
        if (pendingLogs.isEmpty()) {
            return;
        }

        log.info("[WAL Recovery] Found {} pending transactions. Starting recovery...", pendingLogs.size());

        for (WalLogEntry pendingLog : pendingLogs) {
            try {
                log.info("[WAL Recovery] Processing pending log: logId={}, operation={}, txId={}",
                        pendingLog.getLogId(), pendingLog.getOperation(), pendingLog.getTransactionId());

                walService.updateLogStatus(pendingLog.getLogId(), "IN_PROGRESS", "Recovery started by scheduler");

                boolean success = recoverOperation(pendingLog);

                if (success) {
                    walService.updateLogStatus(pendingLog.getLogId(), "RECOVERED", "Successfully recovered by scheduler");
                    log.info("[WAL Recovery] Successfully recovered logId: {}", pendingLog.getLogId());
                } else {
                    walService.updateLogStatus(pendingLog.getLogId(), "PENDING", "Recovery attempt failed, will retry");
                    log.error("[WAL Recovery] Failed to recover logId: {}. Will retry.", pendingLog.getLogId());
                }
            } catch (Exception e) {
                log.error("[WAL Recovery] Critical error during recovery of logId: {}", pendingLog.getLogId(), e);
                try {
                    walService.updateLogStatus(pendingLog.getLogId(), "PENDING", "Recovery attempt failed with exception: " + e.getMessage());
                } catch (Exception e2) {
                    log.error("[WAL Recovery] CRITICAL: Failed to even update log status: {}", pendingLog.getLogId(), e2);
                }
            }
        }
    }

    /**
     * 개별 WAL 로그의 복구 로직
     * [수정] confirmReservation()의 반환값을 InventoryConfirmation.isSuccess()로 변경
     */
    private boolean recoverOperation(WalLogEntry pendingLog) {
        Map<String, String> entityIds = parseEntityIds(pendingLog.getBeforeData());
        String txId = pendingLog.getTransactionId();
        String reservationId = entityIds.get("reservationId");
        String orderId = entityIds.get("orderId");
        String paymentId = entityIds.get("paymentId");
        String phase1LogId = pendingLog.getRelatedLogId();

        switch (pendingLog.getOperation()) {

            case "INVENTORY_RESERVE_START":
            case "ORDER_CREATE_START":
                log.warn("[WAL Recovery] Found PENDING Phase 1: {}. Rolling back.", pendingLog.getOperation());
                return reservationService.cancelReservation(txId, reservationId, "SYSTEM_RECOVERY_PHASE1_FAIL");

            case "PAYMENT_PROCESS_START":
                log.warn("[WAL Recovery] Found PENDING payment: {}. Checking status.", paymentId);
                Payment payment = paymentService.getPayment(paymentId);

                if (payment != null && payment.isCompleted()) {
                    log.info("[WAL Recovery] Payment {} was complete. Rolling forward inventory/order.", paymentId);

                    // [수정] 2. InventoryConfirmation.isSuccess()로 boolean 값 추출
                    InventoryConfirmation invConfirm = inventoryService.confirmReservation(txId, null, reservationId, orderId, paymentId);
                    boolean ord = orderService.markOrderAsPaid(txId, null, orderId, paymentId);

                    return invConfirm.isSuccess() && ord;
                } else {
                    log.info("[WAL Recovery] Payment {} failed or unknown. Rolling back reservation/order.", paymentId);
                    boolean res = reservationService.cancelReservation(txId, reservationId, "SYSTEM_RECOVERY_PAYMENT_FAIL");
                    boolean ord = orderService.cancelOrder(txId, orderId, "SYSTEM_RECOVERY_PAYMENT_FAIL", "Payment Failed Recovery");
                    return res && ord;
                }

            case "INVENTORY_CONFIRM_START":
                log.info("[WAL Recovery] Found PENDING inventory confirm: {}. Retrying.", reservationId);

                // [수정] 3. InventoryConfirmation.isSuccess()로 boolean 값 추출
                InventoryConfirmation confirmResult = inventoryService.confirmReservation(txId, phase1LogId, reservationId, orderId, paymentId);
                return confirmResult.isSuccess();

            case "ORDER_PAYMENT_START":
                log.info("[WAL Recovery] Found PENDING order paid update: {}. Retrying.", orderId);
                return orderService.markOrderAsPaid(txId, phase1LogId, orderId, paymentId);

            default:
                log.warn("[WAL Recovery] Unknown pending operation: {}. Cannot recover.", pendingLog.getOperation());
                return false;
        }
    }

    private Map<String, String> parseEntityIds(String jsonData) {
        if (jsonData == null || jsonData.isEmpty()) {
            return Map.of();
        }
        try {
            String actualJson = jsonData.split("\\|")[0];
            return objectMapper.readValue(actualJson, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.error("[WAL Recovery] Failed to parse entity IDs from JSON: {}", jsonData, e);
            return Map.of();
        }
    }
}
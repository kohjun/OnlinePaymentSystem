package com.example.payment.application.service;

import com.example.payment.application.dto.PaymentGatewayResult;
import com.example.payment.domain.entity.*;
import com.example.payment.domain.repository.*;
import com.example.payment.infrastructure.messaging.outbox.OutboxEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class RefundLedgerWriter {
    private final PaymentRecordRepository paymentRepository;
    private final RefundRecordRepository refundRepository;
    private final OutboxEventService outboxEventService;
    private final ProviderCancellationRepository receipts;
    private final MarketplaceOrderRepository orders;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BeginResult begin(String paymentId, String idempotencyKey, String refundId) {
        orders.findByPaymentId(paymentId).ifPresent(order -> orders.findByIdForUpdate(order.getMarketplaceOrderId()).orElseThrow());
        PaymentRecord payment = paymentRepository.findByIdForUpdate(paymentId).orElse(null);
        if (payment == null) return terminal("PAYMENT_NOT_FOUND", null, null);
        RefundRecord existing = refundRepository.findByPaymentIdAndIdempotencyKey(paymentId, idempotencyKey).orElse(null);
        if (existing != null) return terminal("SUCCEEDED".equals(existing.getStatus()) ? "REFUND_ALREADY_SUCCEEDED" : "REFUND_" + existing.getStatus(), payment, existing);
        var active = refundRepository.findByPaymentIdAndStatusInOrderByCreatedAtAsc(paymentId, Set.of("PROCESSING", "UNKNOWN"));
        if (!active.isEmpty()) return terminal("REFUND_PROCESSING", payment, active.get(0));
        if ("REFUNDED".equals(payment.getStatus())) return terminal("REFUND_ALREADY_SUCCEEDED", payment, null);
        if (!Set.of("APPROVED", "COMPLETED", "PARTIALLY_REFUNDED", "REFUND_FAILED").contains(payment.getStatus())) {
            return terminal("PAYMENT_NOT_REFUNDABLE", payment, null);
        }
        BigDecimal amount = payment.getAmount().subtract(receipts.sumByPaymentId(paymentId));
        RefundRecord refund = refundRepository.saveAndFlush(RefundRecord.builder().refundId(refundId)
                .paymentId(paymentId).idempotencyKey(idempotencyKey).amount(amount).currency(payment.getCurrency())
                .status("PROCESSING").attemptCount(1).createdAt(LocalDateTime.now())
                .sourceType(idempotencyKey.startsWith("saga-") ? "SAGA" : idempotencyKey.startsWith("order-cancel-") ? "CANCELLATION"
                        : idempotencyKey.startsWith("dispute-") ? "DISPUTE" : "ADMIN")
                .sourceId(idempotencyKey).build());
        return new BeginResult(true, "REFUND_PROCESSING", "Refund verification started", payment, refund);
    }

    // Each provider transaction is imported once; local operations never synthesize provider receipt IDs.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ResultRecord observe(String paymentId, PaymentGatewayResult observation, RefundRecord prepared, boolean prepareAmount) {
        PaymentRecord payment = requirePayment(paymentId);
        validate(payment, observation);
        var cancellations = observation.getCancellations() == null ? List.<PaymentGatewayResult.ProviderCancellation>of() : observation.getCancellations();
        Map<String, BigDecimal> completed = new LinkedHashMap<>();
        for (var cancellation : cancellations) {
            if (!"DONE".equals(cancellation.status())) continue;
            if (cancellation.transactionKey() == null || cancellation.amount() == null || cancellation.amount().signum() < 0) {
                throw new IllegalStateException("Invalid provider cancellation receipt");
            }
            BigDecimal duplicate = completed.putIfAbsent(cancellation.transactionKey(), cancellation.amount());
            if (duplicate != null && duplicate.compareTo(cancellation.amount()) != 0) throw new IllegalStateException("Conflicting provider receipt");
        }
        BigDecimal total = completed.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (payment.getAmount().subtract(total).compareTo(observation.getBalanceAmount()) != 0) {
            throw new IllegalStateException("Provider refund balance does not match cancellation receipts");
        }
        for (var entry : completed.entrySet()) {
            var existing = receipts.findByGatewayNameAndTransactionKey(observation.getGatewayName(), entry.getKey()).orElse(null);
            if (existing != null) {
                if (!existing.getPaymentId().equals(paymentId) || existing.getAmount().compareTo(entry.getValue()) != 0) {
                    throw new IllegalStateException("Provider receipt ownership mismatch");
                }
            } else {
                receipts.saveAndFlush(ProviderCancellationRecord.builder()
                        .receiptId("PCR-" + UUID.nameUUIDFromBytes((observation.getGatewayName() + ":" + entry.getKey()).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .gatewayName(observation.getGatewayName()).transactionKey(entry.getKey()).paymentId(paymentId)
                        .amount(entry.getValue()).observedAt(LocalDateTime.now()).build());
            }
        }
        BigDecimal recorded = receipts.sumByPaymentId(paymentId);
        if (recorded.compareTo(total) > 0) return new ResultRecord(payment, prepared == null ? null : requireRefund(prepared));
        String previous = payment.getStatus();
        if (total.signum() > 0) {
            payment.setStatus(observation.getBalanceAmount().signum() == 0 ? "REFUNDED" : "PARTIALLY_REFUNDED");
            payment.setProcessedAt(LocalDateTime.now());
            payment.setFailureReason(null);
            paymentRepository.save(payment);
        }
        RefundRecord refund = prepared == null ? null : requireRefund(prepared);
        boolean changed = false;
        if (refund != null && !"SUCCEEDED".equals(refund.getStatus())) {
            refund.setUpdatedAt(LocalDateTime.now());
            if (observation.getBalanceAmount().signum() == 0) {
                changed = true;
                refund.setStatus("SUCCEEDED");
                refund.setFailureReason(null);
                refund.setCompletedAt(LocalDateTime.now());
                refund.setProviderRefundId(completed.keySet().stream().reduce((a, b) -> b).orElse(null));
            } else if (prepareAmount) {
                refund.setAmount(observation.getBalanceAmount());
            }
            refundRepository.saveAndFlush(refund);
        }
        if (changed || !previous.equals(payment.getStatus())) recordOutbox(payment, refund, "PAYMENT_REFUND_OBSERVED");
        return new ResultRecord(payment, refund);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ResultRecord unknown(String paymentId, RefundRecord refund, String failureReason, String reason) {
        return recordFailure(paymentId, refund, "UNKNOWN", "REFUND_UNKNOWN", failureReason);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ResultRecord failed(String paymentId, RefundRecord refund, String failureReason, String reason) {
        return recordFailure(paymentId, refund, "FAILED", "REFUND_FAILED", failureReason);
    }

    private ResultRecord recordFailure(String paymentId, RefundRecord prepared, String status, String paymentStatus, String reason) {
        PaymentRecord payment = requirePayment(paymentId);
        RefundRecord refund = requireRefund(prepared);
        if ("SUCCEEDED".equals(refund.getStatus()) || "REFUNDED".equals(payment.getStatus())) return new ResultRecord(payment, refund);
        if ("FAILED".equals(refund.getStatus()) && "UNKNOWN".equals(status)) return new ResultRecord(payment, refund);
        boolean changed = !status.equals(refund.getStatus());
        refund.setStatus(status);
        refund.setUpdatedAt(LocalDateTime.now());
        refund.setFailureReason(reason);
        refundRepository.save(refund);
        payment.setStatus(paymentStatus);
        payment.setFailureReason(reason);
        payment.setProcessedAt(LocalDateTime.now());
        paymentRepository.save(payment);
        if (changed) recordOutbox(payment, refund, "PAYMENT_" + paymentStatus);
        return new ResultRecord(payment, refund);
    }

    private void validate(PaymentRecord payment, PaymentGatewayResult observation) {
        if (observation == null || !Set.of("APPROVED", "REFUNDED", "PARTIALLY_REFUNDED").contains(
                Objects.toString(observation.getGatewayStatus(), "")) || observation.getGatewayName() == null
                || !Objects.equals(payment.getOrderId(), observation.getOrderId())
                || !Objects.equals(payment.getTransactionId(), observation.getTransactionId())
                || !Objects.equals(payment.getCurrency(), observation.getCurrency())
                || observation.getProcessedAmount() == null || payment.getAmount().compareTo(observation.getProcessedAmount()) != 0
                || observation.getBalanceAmount() == null || observation.getBalanceAmount().signum() < 0
                || observation.getBalanceAmount().compareTo(payment.getAmount()) > 0) {
            throw new IllegalStateException("Provider refund observation could not be verified");
        }
    }
    private PaymentRecord requirePayment(String id) { return paymentRepository.findByIdForUpdate(id).orElseThrow(); }
    private RefundRecord requireRefund(RefundRecord refund) { return refundRepository.findById(refund.getRefundId()).orElseThrow(); }
    private BeginResult terminal(String code, PaymentRecord payment, RefundRecord refund) { return new BeginResult(false, code, code, payment, refund); }
    private void recordOutbox(PaymentRecord payment, RefundRecord refund, String type) {
        Map<String,Object> payload = new LinkedHashMap<>();
        payload.put("paymentId", payment.getPaymentId()); payload.put("orderId", payment.getOrderId());
        payload.put("reservationId", payment.getReservationId()); payload.put("status", payment.getStatus());
        payload.put("refundId", refund == null ? null : refund.getRefundId());
        outboxEventService.record("PAYMENT", payment.getPaymentId(), type, "payment-events", payment.getPaymentId(), payload);
    }
    public record BeginResult(boolean ready, String code, String message, PaymentRecord payment, RefundRecord refund) {}
    public record ResultRecord(PaymentRecord payment, RefundRecord refund) {}
}

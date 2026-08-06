package com.example.payment.application.service;

import com.example.payment.domain.entity.PaymentRecord;
import com.example.payment.domain.entity.RefundRecord;
import com.example.payment.domain.model.payment.PaymentStatus;
import com.example.payment.domain.repository.PaymentRecordRepository;
import com.example.payment.domain.repository.RefundRecordRepository;
import com.example.payment.infrastructure.messaging.outbox.OutboxEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RefundLedgerWriter {

    private final PaymentRecordRepository paymentRepository;
    private final RefundRecordRepository refundRepository;
    private final OutboxEventService outboxEventService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BeginResult begin(String paymentId, String idempotencyKey, String refundId) {
        PaymentRecord payment = paymentRepository.findById(paymentId).orElse(null);
        if (payment == null) {
            return BeginResult.terminal("PAYMENT_NOT_FOUND", "Payment not found.", null, null);
        }

        RefundRecord refund = refundRepository.findByPaymentIdAndIdempotencyKey(paymentId, idempotencyKey)
                .orElseGet(() -> refundRepository.save(RefundRecord.builder()
                        .refundId(refundId)
                        .paymentId(paymentId)
                        .idempotencyKey(idempotencyKey)
                        .amount(payment.getAmount())
                        .currency(payment.getCurrency())
                        .status("PROCESSING")
                        .attemptCount(0)
                        .createdAt(LocalDateTime.now())
                        .build()));

        if ("SUCCEEDED".equals(refund.getStatus())) {
            applyRefundedPaymentStatus(payment);
            return BeginResult.terminal("REFUND_ALREADY_SUCCEEDED", "Refund already succeeded.", payment, refund);
        }
        if (!isRefundable(payment.getStatus())) {
            return BeginResult.terminal(
                    "PAYMENT_NOT_REFUNDABLE",
                    "Payment is not refundable in status " + payment.getStatus() + ".",
                    payment,
                    refund
            );
        }

        refund.setAttemptCount(refund.getAttemptCount() + 1);
        refund.setStatus("PROCESSING");
        refund.setFailureReason(null);
        refundRepository.save(refund);
        return BeginResult.ready(payment, refund);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ResultRecord succeed(String paymentId, RefundRecord preparedRefund, String providerRefundId, String reason) {
        PaymentRecord payment = requirePayment(paymentId);
        RefundRecord refund = requireRefund(preparedRefund);
        refund.setStatus("SUCCEEDED");
        refund.setProviderRefundId(providerRefundId);
        refund.setCompletedAt(LocalDateTime.now());
        refund.setFailureReason(null);
        refundRepository.saveAndFlush(refund);
        applyRefundedPaymentStatus(payment);
        recordOutbox(payment, refund, "PAYMENT_REFUNDED", reason);
        return new ResultRecord(payment, refund);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ResultRecord unknown(String paymentId, RefundRecord preparedRefund, String failureReason, String reason) {
        return recordFailure(paymentId, preparedRefund, "UNKNOWN", PaymentStatus.REFUND_UNKNOWN, failureReason,
                "PAYMENT_REFUND_UNKNOWN", reason);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ResultRecord failed(String paymentId, RefundRecord preparedRefund, String failureReason, String reason) {
        return recordFailure(paymentId, preparedRefund, "FAILED", PaymentStatus.REFUND_FAILED, failureReason,
                "PAYMENT_REFUND_FAILED", reason);
    }

    private ResultRecord recordFailure(String paymentId, RefundRecord preparedRefund, String refundStatus,
                                       PaymentStatus paymentStatus, String failureReason,
                                       String eventType, String reason) {
        PaymentRecord payment = requirePayment(paymentId);
        RefundRecord refund = requireRefund(preparedRefund);
        refund.setStatus(refundStatus);
        refund.setFailureReason(failureReason);
        refundRepository.save(refund);

        payment.setStatus(paymentStatus.name());
        payment.setFailureReason(failureReason);
        payment.setProcessedAt(LocalDateTime.now());
        paymentRepository.save(payment);
        recordOutbox(payment, refund, eventType, reason);
        return new ResultRecord(payment, refund);
    }

    private void applyRefundedPaymentStatus(PaymentRecord payment) {
        BigDecimal refundedAmount = refundRepository.sumSucceededAmountByPaymentId(payment.getPaymentId());
        payment.setStatus(refundedAmount != null && refundedAmount.compareTo(payment.getAmount()) >= 0
                ? PaymentStatus.REFUNDED.name()
                : PaymentStatus.PARTIALLY_REFUNDED.name());
        payment.setFailureReason(null);
        payment.setProcessedAt(LocalDateTime.now());
        paymentRepository.save(payment);
    }

    private boolean isRefundable(String status) {
        return PaymentStatus.APPROVED.name().equals(status)
                || PaymentStatus.COMPLETED.name().equals(status)
                || PaymentStatus.PARTIALLY_REFUNDED.name().equals(status)
                || PaymentStatus.REFUND_UNKNOWN.name().equals(status)
                || PaymentStatus.REFUND_FAILED.name().equals(status);
    }

    private PaymentRecord requirePayment(String paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalStateException("Payment not found while recording refund: " + paymentId));
    }

    private RefundRecord requireRefund(RefundRecord preparedRefund) {
        return refundRepository.findById(preparedRefund.getRefundId()).orElse(preparedRefund);
    }

    private void recordOutbox(PaymentRecord payment, RefundRecord refund, String eventType, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentId", payment.getPaymentId());
        payload.put("orderId", payment.getOrderId());
        payload.put("reservationId", payment.getReservationId());
        payload.put("customerId", payment.getCustomerId());
        payload.put("refundId", refund.getRefundId());
        payload.put("amount", refund.getAmount());
        payload.put("currency", refund.getCurrency());
        payload.put("paymentStatus", payment.getStatus());
        payload.put("refundStatus", refund.getStatus());
        payload.put("reason", reason);
        if (refund.getFailureReason() != null) {
            payload.put("failureReason", refund.getFailureReason());
        }
        outboxEventService.record("PAYMENT", payment.getPaymentId(), eventType,
                "payment-events", payment.getPaymentId(), payload);
    }

    public record BeginResult(boolean ready, String code, String message,
                              PaymentRecord payment, RefundRecord refund) {
        static BeginResult ready(PaymentRecord payment, RefundRecord refund) {
            return new BeginResult(true, "REFUND_PROCESSING", "Refund processing started.", payment, refund);
        }

        static BeginResult terminal(String code, String message, PaymentRecord payment, RefundRecord refund) {
            return new BeginResult(false, code, message, payment, refund);
        }
    }

    public record ResultRecord(PaymentRecord payment, RefundRecord refund) {
    }
}

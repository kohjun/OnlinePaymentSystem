package com.example.payment.application.service;

import com.example.payment.application.dto.PaymentRefundRequest;
import com.example.payment.domain.entity.PaymentRecord;
import com.example.payment.domain.entity.RefundRecord;
import com.example.payment.domain.exception.PaymentGatewayResultUnknownException;
import com.example.payment.domain.service.PaymentGatewayService;
import com.example.payment.infrastructure.gateway.PaymentGatewayFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class RefundCoordinator {

    private final PaymentGatewayFactory gatewayFactory;
    private final RefundLedgerWriter ledgerWriter;

    public RefundOutcome refund(String paymentId, String idempotencyKey, String reason) {
        String effectiveKey = defaultText(idempotencyKey, "manual-" + paymentId);
        String refundId = "RF-" + paymentId + "-" + effectiveKey;
        String refundReason = defaultText(reason, "Manual refund request");

        RefundLedgerWriter.BeginResult begin = ledgerWriter.begin(paymentId, effectiveKey, refundId);
        if (!begin.ready()) {
            boolean succeeded = "REFUND_ALREADY_SUCCEEDED".equals(begin.code());
            return new RefundOutcome(succeeded, begin.code(), begin.message(), begin.payment(), begin.refund());
        }

        PaymentRecord payment = begin.payment();
        RefundRecord refund = begin.refund();
        try {
            PaymentGatewayService gateway = gatewayFactory.getGateway(payment.getMethod());
            boolean refunded = gateway.refundPayment(PaymentRefundRequest.builder()
                    .transactionId(payment.getTransactionId())
                    .idempotencyKey(refund.getIdempotencyKey())
                    .amount(refund.getAmount())
                    .currency(refund.getCurrency())
                    .reason(refundReason)
                    .build());
            if (refunded) {
                RefundLedgerWriter.ResultRecord result = ledgerWriter.succeed(
                        paymentId, refund, refundId, refundReason);
                return new RefundOutcome(true, "REFUND_SUCCEEDED", "Payment refunded successfully.",
                        result.payment(), result.refund());
            }
            RefundLedgerWriter.ResultRecord result = ledgerWriter.failed(
                    paymentId, refund, "PG refund returned false", refundReason);
            return new RefundOutcome(false, "REFUND_FAILED", "Payment refund failed.",
                    result.payment(), result.refund());
        } catch (PaymentGatewayResultUnknownException exception) {
            RefundLedgerWriter.ResultRecord result = ledgerWriter.unknown(
                    paymentId, refund, exception.getMessage(), refundReason);
            return new RefundOutcome(false, "REFUND_UNKNOWN",
                    "Refund result is not confirmed yet.", result.payment(), result.refund());
        } catch (Exception exception) {
            log.warn("Refund failed: paymentId={}, refundId={}, reason={}",
                    paymentId, refundId, exception.getMessage());
            RefundLedgerWriter.ResultRecord result = ledgerWriter.failed(
                    paymentId, refund, exception.getMessage(), refundReason);
            return new RefundOutcome(false, "REFUND_FAILED", "System error while refunding payment.",
                    result.payment(), result.refund());
        }
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    public record RefundOutcome(boolean success, String code, String message,
                                PaymentRecord payment, RefundRecord refund) {
    }
}

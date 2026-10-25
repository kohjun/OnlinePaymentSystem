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
        if (begin.refund() == null || "REFUND_ALREADY_SUCCEEDED".equals(begin.code())) {
            return new RefundOutcome("REFUND_ALREADY_SUCCEEDED".equals(begin.code()), begin.code(), begin.message(), begin.payment(), begin.refund());
        }
        PaymentRecord payment = begin.payment();
        RefundRecord refund = begin.refund();
        PaymentGatewayService gateway = gatewayFactory.getGateway(payment.getMethod());
        try {
            var observed = ledgerWriter.observe(paymentId, gateway.getPaymentStatus(payment.getTransactionId()), refund, begin.ready());
            if ("SUCCEEDED".equals(observed.refund().getStatus())) return outcome(observed);
            if (!begin.ready()) return outcome(observed);
            refund = observed.refund();
        } catch (Exception unavailable) {
            return outcome(ledgerWriter.unknown(paymentId, refund, "Provider refund lookup unavailable", refundReason));
        }

        com.example.payment.application.dto.PaymentRefundResult result;
        try {
            result = gateway.refund(PaymentRefundRequest.builder().transactionId(payment.getTransactionId())
                    .idempotencyKey(refund.getIdempotencyKey()).amount(refund.getAmount())
                    .currency(refund.getCurrency()).reason(refundReason).build());
        } catch (Exception unavailable) {
            result = com.example.payment.application.dto.PaymentRefundResult.unknown("Provider refund result unavailable");
        }
        if (result == null || result.status() == com.example.payment.application.dto.PaymentRefundResult.Status.UNKNOWN) {
            return outcome(ledgerWriter.unknown(paymentId, refund, "Provider refund result unavailable", refundReason));
        }
        if (result.status() == com.example.payment.application.dto.PaymentRefundResult.Status.FAILED) {
            return outcome(ledgerWriter.failed(paymentId, refund, result.message(), refundReason));
        }
        // Let DB failures propagate. Retrying the coordinator will query, never resubmit, this operation.
        return outcome(ledgerWriter.observe(paymentId, result.payment(), refund, false));
    }

    private RefundOutcome outcome(RefundLedgerWriter.ResultRecord record) {
        boolean success = record.refund() != null && "SUCCEEDED".equals(record.refund().getStatus());
        String status = record.refund() == null ? "UNKNOWN" : record.refund().getStatus();
        return new RefundOutcome(success, "REFUND_" + status, success ? "Refund completed" : "Refund verification required",
                record.payment(), record.refund());
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    public record RefundOutcome(boolean success, String code, String message,
                                PaymentRecord payment, RefundRecord refund) {
    }
}

package com.example.payment.application.service;

import com.example.payment.domain.repository.*;
import com.example.payment.domain.model.marketplace.OrderCancellationStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.data.domain.PageRequest;
import java.util.Set;

@Service @RequiredArgsConstructor @Slf4j
public class RefundRecoveryService {
    private final RefundRecordRepository refunds;
    private final PaymentRecordRepository payments;
    private final MarketplaceOrderCancellationRepository cancellations;
    private final RefundCoordinator coordinator;
    private final OrderCancellationLedgerWriter cancellationWriter;
    private final MarketplaceOrderService orders;

    @Scheduled(fixedDelayString = "${payment.recovery.fixed-delay-ms:60000}")
    public void reconcile() {
        Set<String> ids = new java.util.LinkedHashSet<>();
        refunds.findByStatusInOrderByUpdatedAtAsc(Set.of("PROCESSING", "UNKNOWN", "FAILED"), PageRequest.of(0, 50))
                .forEach(refund -> ids.add(refund.getPaymentId()));
        cancellations.findByStatusNotOrderByUpdatedAtAsc(OrderCancellationStatus.COMPLETED, PageRequest.of(0, 50))
                .forEach(cancellation -> ids.add(cancellation.getPaymentId()));
        payments.findRefundsNeedingFinalization(PageRequest.of(0, 50)).forEach(payment -> ids.add(payment.getPaymentId()));
        for (String id : ids) {
            try { recoverPayment(id); }
            catch (RuntimeException failure) { log.warn("Refund recovery deferred: paymentId={}", id); }
        }
    }

    public void recoverPayment(String paymentId) {
        for (var refund : refunds.findByPaymentIdAndStatusInOrderByCreatedAtAsc(paymentId, Set.of("PROCESSING", "UNKNOWN", "FAILED"))) {
            coordinator.refund(paymentId, refund.getIdempotencyKey(), "Provider refund reconciliation");
        }
        var cancellation = cancellations.findByPaymentId(paymentId).orElse(null);
        if (cancellation != null && cancellation.getStatus() != OrderCancellationStatus.COMPLETED) {
            var result = coordinator.refund(paymentId, cancellation.getRefundIdempotencyKey(), "Order cancellation recovery");
            cancellationWriter.finish(cancellation.getCancellationId(), result);
        } else {
            payments.findById(paymentId).ifPresent(payment -> orders.syncProviderRefundStatus(paymentId, payment.getStatus()));
        }
    }
}

package com.example.payment.application.service;

import com.example.payment.infrastructure.security.CommerceActor;
import com.example.payment.presentation.dto.response.OrderCancellationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderCancellationService {

    private final OrderCancellationLedgerWriter ledgerWriter;
    private final RefundCoordinator refundCoordinator;

    public OrderCancellationResponse cancel(String orderId, CommerceActor actor, String reason) {
        OrderCancellationLedgerWriter.BeginResult begin = ledgerWriter.begin(orderId, actor, reason);
        if (!begin.ready()) {
            return ledgerWriter.toResponse(begin.cancellation());
        }
        RefundCoordinator.RefundOutcome outcome = refundCoordinator.refund(
                begin.cancellation().getPaymentId(),
                begin.cancellation().getRefundIdempotencyKey(),
                reason != null && !reason.isBlank() ? reason : "Marketplace order cancellation"
        );
        return ledgerWriter.toResponse(ledgerWriter.finish(
                begin.cancellation().getCancellationId(), outcome));
    }
}

package com.example.payment.application.service;

import com.example.payment.domain.model.marketplace.MarketplaceOrder;
import com.example.payment.domain.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Set;

@Service @RequiredArgsConstructor
public class CommerceSettlementGuard {
    private final MarketplaceOrderCancellationRepository cancellations;
    private final RefundRecordRepository refunds;
    private final PaymentRecordRepository payments;

    public void requireClear(MarketplaceOrder order) {
        if (cancellations.findByMarketplaceOrderId(order.getMarketplaceOrderId()).isPresent()
                || !refunds.findByPaymentIdAndStatusInOrderByCreatedAtAsc(order.getPaymentId(),
                        Set.of("PROCESSING", "UNKNOWN", "FAILED", "SUCCEEDED")).isEmpty()
                || payments.findById(order.getPaymentId()).map(p -> !Set.of("APPROVED", "COMPLETED").contains(p.getStatus())).orElse(true)) {
            throw new CommerceSettlementBlockedException();
        }
    }
}

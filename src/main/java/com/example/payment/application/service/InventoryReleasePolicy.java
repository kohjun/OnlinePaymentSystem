package com.example.payment.application.service;

import com.example.payment.domain.entity.InventoryReservationRecord;
import com.example.payment.domain.repository.PaymentRecordRepository;
import com.example.payment.domain.repository.TossPaymentIntentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class InventoryReleasePolicy {
    private final PaymentRecordRepository payments;
    private final TossPaymentIntentRepository intents;

    public boolean mayRelease(InventoryReservationRecord reservation) {
        var payment = payments.findByReservationId(reservation.getReservationId()).orElse(null);
        if (payment != null) return Set.of("FAILED", "REFUNDED").contains(payment.getStatus());
        if ("STARTED".equals(reservation.getAuthorizationState())) return false;
        if ("CLOSED_WITHOUT_AUTHORIZATION".equals(reservation.getAuthorizationState())
                && reservation.getAuthorizationClosedAt() != null
                && reservation.getAuthorizationCloseWorkflowId() != null
                && !reservation.getAuthorizationCloseWorkflowId().isBlank()) return true;
        if (reservation.getClaimedIntentId() == null) return true;
        var intent = intents.findById(reservation.getClaimedIntentId()).orElse(null);
        return intent != null && intent.getPaymentKey() == null && intent.getWorkflowId() == null
                && Set.of("READY", "CANCELLED", "CANCELED", "FAILED").contains(intent.getStatus());
    }
}

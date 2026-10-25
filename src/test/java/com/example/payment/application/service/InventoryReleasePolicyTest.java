package com.example.payment.application.service;

import com.example.payment.domain.entity.*;
import com.example.payment.domain.repository.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InventoryReleasePolicyTest {
    private final PaymentRecordRepository payments = mock(PaymentRecordRepository.class);
    private final TossPaymentIntentRepository intents = mock(TossPaymentIntentRepository.class);
    private final InventoryReleasePolicy policy = new InventoryReleasePolicy(payments, intents);
    private final InventoryReservationRecord hold = InventoryReservationRecord.builder()
            .reservationId("H").claimedIntentId("I").build();

    @ParameterizedTest @ValueSource(strings = {"PROCESSING", "UNKNOWN", "APPROVED", "REFUND_UNKNOWN", "REFUND_FAILED", "PARTIALLY_REFUNDED"})
    void uncertainOrChargedPaymentsBlockExpiry(String status) {
        when(payments.findByReservationId("H")).thenReturn(Optional.of(PaymentRecord.builder().status(status).build()));
        assertFalse(policy.mayRelease(hold));
    }

    @ParameterizedTest @ValueSource(strings = {"FAILED", "REFUNDED"})
    void confirmedTerminalUnchargedPaymentsMayRelease(String status) {
        when(payments.findByReservationId("H")).thenReturn(Optional.of(PaymentRecord.builder().status(status).build()));
        assertTrue(policy.mayRelease(hold));
    }

    @Test void authenticatedIntentIsProtectedEvenBeforePaymentRowExists() {
        when(intents.findById("I")).thenReturn(Optional.of(TossPaymentIntent.builder().status("AUTHENTICATED").paymentKey("KEY").build()));
        assertFalse(policy.mayRelease(hold));
    }

    @Test void authenticatedIntentCanReleaseOnlyWithPersistedClosureProof() {
        hold.setAuthorizationState("CLOSED_WITHOUT_AUTHORIZATION");
        assertFalse(policy.mayRelease(hold));
        hold.setAuthorizationClosedAt(java.time.LocalDateTime.now());
        hold.setAuthorizationCloseWorkflowId("WORKFLOW");
        assertTrue(policy.mayRelease(hold));
    }

    @Test void missingPaymentRowAfterStartedClaimStillBlocksRelease() {
        hold.setAuthorizationState("STARTED");
        hold.setClaimedIntentId(null);
        assertFalse(policy.mayRelease(hold));
    }

    @Test void paymentEvidenceOverridesContradictoryClosureProof() {
        hold.setAuthorizationState("CLOSED_WITHOUT_AUTHORIZATION");
        hold.setAuthorizationClosedAt(java.time.LocalDateTime.now());
        hold.setAuthorizationCloseWorkflowId("WORKFLOW");
        when(payments.findByReservationId("H")).thenReturn(Optional.of(PaymentRecord.builder().status("UNKNOWN").build()));
        assertFalse(policy.mayRelease(hold));
    }
}

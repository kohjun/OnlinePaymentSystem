package com.example.payment.application.service;

import com.example.payment.application.temporal.ReservationWorkflowCommand;
import com.example.payment.domain.entity.InventoryReservationRecord;
import com.example.payment.domain.entity.PaymentRecord;
import com.example.payment.domain.repository.PaymentRecordRepository;
import com.example.payment.infrastructure.messaging.outbox.OutboxEventService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReservationAuthorizationFenceTest {
    private final PaymentRecordRepository payments = mock(PaymentRecordRepository.class);
    private final OutboxEventService outbox = mock(OutboxEventService.class);
    private final ReservationAuthorizationFence fence = new ReservationAuthorizationFence(payments, outbox);

    private InventoryReservationRecord hold() {
        return InventoryReservationRecord.builder().reservationId("H").productId("PRODUCT").customerId("BUYER")
                .quantity(1).claimedIntentId("INTENT").status("RESERVED").authorizationState("NOT_STARTED").build();
    }

    private ReservationWorkflowCommand command() {
        return ReservationWorkflowCommand.builder().workflowId("WORKFLOW").reservationId("H").productId("PRODUCT")
                .customerId("BUYER").quantity(1).tossIntentId("INTENT").build();
    }

    @Test void closurePermanentlyBlocksLateAuthorizationAndRecordsProofOnce() {
        var hold = hold();
        fence.closeBeforeAuthorization(hold, command());
        fence.closeBeforeAuthorization(hold, command());
        assertEquals("CLOSED_WITHOUT_AUTHORIZATION", hold.getAuthorizationState());
        assertEquals("WORKFLOW", hold.getAuthorizationCloseWorkflowId());
        assertNotNull(hold.getAuthorizationClosedAt());
        assertNull(hold.getAuthorizationStartedAt());
        assertThrows(IllegalStateException.class, () -> fence.start(hold, command()));
        verify(outbox, times(1)).record(any(), any(), any(), any(), any(), any());
    }

    @Test void authorizationClaimPreventsProofEvenWithoutPaymentRow() {
        var hold = hold();
        fence.start(hold, command());
        fence.closeBeforeAuthorization(hold, command());
        assertEquals("STARTED", hold.getAuthorizationState());
        assertNotNull(hold.getAuthorizationStartedAt());
        assertNull(hold.getAuthorizationClosedAt());
        assertThrows(IllegalStateException.class, () -> fence.start(hold, command()));
        verifyNoInteractions(outbox);
    }

    @Test void migratedUntrackedHoldCannotGainNoAuthorizationProof() {
        var hold = hold();
        hold.setAuthorizationState("UNTRACKED");
        fence.closeBeforeAuthorization(hold, command());
        assertEquals("UNTRACKED", hold.getAuthorizationState());
        assertNull(hold.getAuthorizationClosedAt());
        verifyNoInteractions(outbox);
    }

    @ParameterizedTest @ValueSource(strings = {"PROCESSING", "UNKNOWN", "APPROVED", "FAILED", "REFUNDED"})
    void existingPaymentPreventsAnyNoSubmissionProof(String status) {
        var hold = hold();
        when(payments.findByReservationId("H")).thenReturn(Optional.of(PaymentRecord.builder().status(status).build()));
        fence.closeBeforeAuthorization(hold, command());
        assertEquals("NOT_STARTED", hold.getAuthorizationState());
        assertThrows(IllegalStateException.class, () -> fence.start(hold, command()));
        verifyNoInteractions(outbox);
    }

    @Test void differentIntentCannotCloseOrStartAuthorization() {
        var hold = hold();
        var command = command();
        command.setTossIntentId("OTHER");
        assertThrows(IllegalStateException.class, () -> fence.closeBeforeAuthorization(hold, command));
        assertThrows(IllegalStateException.class, () -> fence.start(hold, command));
        verifyNoInteractions(outbox);
    }
}

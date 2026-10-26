package com.example.payment.application.service;

import com.example.payment.application.dto.PaymentGatewayResult;
import com.example.payment.application.temporal.ReservationWorkflowCommand;
import com.example.payment.domain.entity.PaymentRecord;
import com.example.payment.domain.entity.InventoryReservationRecord;
import com.example.payment.domain.repository.*;
import com.example.payment.infrastructure.messaging.outbox.OutboxEventService;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class PaymentObservationWriterTest {
    private final PaymentRecordRepository payments = mock(PaymentRecordRepository.class);
    private final InventoryReservationRecordRepository holds = mock(InventoryReservationRecordRepository.class);
    private final OutboxEventService outbox = mock(OutboxEventService.class);
    private final PaymentObservationWriter writer = new PaymentObservationWriter(payments, holds, outbox,
            new ReservationAuthorizationFence(payments, outbox));

    @Test void existingProcessingPaymentIsNeverAuthorizedAgain() {
        var command = command();
        when(holds.findByIdForUpdate("H-1")).thenReturn(Optional.of(InventoryReservationRecord.builder().status("RESERVED").build()));
        stubPayment("PROCESSING");
        assertFalse(writer.begin(command).authorize());
        verify(payments, never()).saveAndFlush(any());
    }

    @Test void mismatchedProviderResponseIsUncertainNotApproved() {
        stubPayment("PROCESSING");
        var result = approved();
        result.setOrderId("SOMEONE-ELSES-ORDER");
        assertEquals("UNKNOWN", writer.apply(command(), result).getStatus());
    }

    @Test void approvalEventIsWrittenOnlyOnceAndLateErrorsCannotRegressIt() {
        stubPayment("PROCESSING");
        writer.apply(command(), approved());
        writer.apply(command(), approved());
        assertEquals("APPROVED", writer.apply(command(), PaymentGatewayResult.unknown("timeout")).getStatus());
        verify(outbox, times(1)).record(any(), any(), any(), any(), any(), any());
    }

    @Test void refundStateIsNeverReplacedByApproval() {
        stubPayment("REFUND_UNKNOWN");
        assertEquals("REFUND_UNKNOWN", writer.apply(command(), approved()).getStatus());
        verifyNoInteractions(outbox);
    }

    private void stubPayment(String status) {
        when(payments.findByIdForUpdate("P-1")).thenReturn(Optional.of(PaymentRecord.builder()
                .paymentId("P-1").status(status).build()));
    }

    private ReservationWorkflowCommand command() {
        return ReservationWorkflowCommand.builder().paymentId("P-1").reservationId("H-1")
                .orderId("O-1").tossOrderId("O-1").tossPaymentKey("KEY-1")
                .amount(new BigDecimal("10000")).currency("KRW").build();
    }

    private PaymentGatewayResult approved() {
        return PaymentGatewayResult.builder().success(true).gatewayStatus("APPROVED").gatewayName("TOSS_PAYMENTS")
                .transactionId("KEY-1").orderId("O-1").processedAmount(new BigDecimal("10000")).currency("KRW").build();
    }
}

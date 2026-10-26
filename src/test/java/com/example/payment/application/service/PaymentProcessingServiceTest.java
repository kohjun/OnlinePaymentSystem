package com.example.payment.application.service;

import com.example.payment.domain.entity.PaymentRecord;
import com.example.payment.domain.repository.*;
import com.example.payment.infrastructure.gateway.PaymentGatewayFactory;
import com.example.payment.infrastructure.messaging.outbox.OutboxEventService;
import com.example.payment.infrastructure.persistence.redis.repository.CacheService;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentProcessingServiceTest {
    private final PaymentRecordRepository payments = mock(PaymentRecordRepository.class);
    private final RefundCoordinator refunds = mock(RefundCoordinator.class);
    private final PaymentProcessingService service = new PaymentProcessingService(mock(PaymentGatewayFactory.class),
            mock(CacheService.class), payments, mock(RefundRecordRepository.class), mock(OutboxEventService.class), refunds);

    @Test void refundDelegatesToTheTransactionalCoordinator() {
        PaymentRecord record = payment("REFUNDED");
        when(refunds.refund("PAY-1", "REF-1", "buyer request")).thenReturn(
                new RefundCoordinator.RefundOutcome(true, "REFUND_SUCCEEDED", "done", record, null));
        var result = service.refundPaymentWithResult("PAY-1", "REF-1", "buyer request");
        assertTrue(result.success());
        assertEquals("REFUND_SUCCEEDED", result.code());
        verify(refunds).refund("PAY-1", "REF-1", "buyer request");
    }

    @Test void refundUnknownRemainsUnknown() {
        when(refunds.refund("PAY-1", "REF-1", "buyer request")).thenReturn(
                new RefundCoordinator.RefundOutcome(false, "REFUND_UNKNOWN", "verify", payment("REFUND_UNKNOWN"), null));
        var result = service.refundPaymentWithResult("PAY-1", "REF-1", "buyer request");
        assertFalse(result.success());
        assertEquals("REFUND_UNKNOWN", result.code());
        verifyNoInteractions(payments);
    }

    @Test void failedRefundDoesNotClaimSuccess() {
        when(refunds.refund("PAY-1", "REF-1", "buyer request")).thenReturn(
                new RefundCoordinator.RefundOutcome(false, "REFUND_FAILED", "rejected", payment("REFUND_FAILED"), null));
        assertFalse(service.refundPaymentWithResult("PAY-1", "REF-1", "buyer request").success());
    }

    @Test void getPaymentByReservationUsesDatabase() {
        when(payments.findByReservationId("RES-1")).thenReturn(Optional.of(payment("APPROVED")));
        assertNotNull(service.getPaymentByReservationId("RES-1"));
    }

    private PaymentRecord payment(String status) {
        return PaymentRecord.builder().paymentId("PAY-1").orderId("ORD-1").reservationId("RES-1").customerId("CUS-1")
                .amount(new BigDecimal("100.00")).currency("KRW").method("CREDIT_CARD").status(status)
                .transactionId("test-key").createdAt(LocalDateTime.now()).build();
    }
}

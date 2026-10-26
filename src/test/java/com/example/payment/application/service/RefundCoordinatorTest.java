package com.example.payment.application.service;

import com.example.payment.application.dto.*;
import com.example.payment.domain.entity.*;
import com.example.payment.domain.service.PaymentGatewayService;
import com.example.payment.infrastructure.gateway.PaymentGatewayFactory;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RefundCoordinatorTest {
    private final PaymentGatewayFactory factory = mock(PaymentGatewayFactory.class);
    private final PaymentGatewayService gateway = mock(PaymentGatewayService.class);
    private final RefundLedgerWriter writer = mock(RefundLedgerWriter.class);
    private final RefundCoordinator coordinator = new RefundCoordinator(factory, writer);
    private final PaymentRecord payment = PaymentRecord.builder().paymentId("P").transactionId("KEY")
            .method("CARD").amount(new BigDecimal("10000")).currency("KRW").build();
    private final RefundRecord refund = RefundRecord.builder().refundId("R").paymentId("P").idempotencyKey("IDEM")
            .amount(new BigDecimal("7000")).currency("KRW").status("PROCESSING").build();

    @Test void pendingRefundOnlyQueriesAndNeverSubmitsAgain() {
        setup(false);
        assertFalse(coordinator.refund("P", "IDEM", "cancel").success());
        verify(gateway).getPaymentStatus("KEY");
        verify(gateway, never()).refund(any());
    }

    @Test void remainingAmountFromProviderIsUsedInsteadOfOriginalAmount() {
        setup(true);
        when(gateway.refund(any())).thenReturn(PaymentRefundResult.unknown("timeout"));
        when(writer.unknown(any(), any(), any(), any())).thenReturn(new RefundLedgerWriter.ResultRecord(payment, refund));
        coordinator.refund("P", "IDEM", "cancel");
        verify(gateway).refund(argThat(request -> request.getAmount().compareTo(new BigDecimal("7000")) == 0
                && request.getIdempotencyKey().equals("IDEM")));
        verify(writer, never()).failed(any(), any(), any(), any());
    }

    @Test void databaseFailureAfterProviderSuccessIsNotRecordedAsRefundRejection() {
        setup(true);
        var observation = PaymentGatewayResult.builder().gatewayStatus("REFUNDED").build();
        when(gateway.refund(any())).thenReturn(new PaymentRefundResult(PaymentRefundResult.Status.SUCCEEDED, observation, null));
        when(writer.observe(eq("P"), same(observation), same(refund), eq(false)))
                .thenThrow(new IllegalStateException("DB unavailable after provider success"));
        assertThrows(IllegalStateException.class, () -> coordinator.refund("P", "IDEM", "cancel"));
        verify(writer, never()).failed(any(), any(), any(), any());
        verify(writer, never()).unknown(any(), any(), any(), any());
    }

    private void setup(boolean ready) {
        when(factory.getGateway("CARD")).thenReturn(gateway);
        when(writer.begin(eq("P"), eq("IDEM"), any())).thenReturn(new RefundLedgerWriter.BeginResult(ready,
                "REFUND_PROCESSING", "pending", payment, refund));
        when(writer.observe(eq("P"), any(), same(refund), eq(ready)))
                .thenReturn(new RefundLedgerWriter.ResultRecord(payment, refund));
    }
}

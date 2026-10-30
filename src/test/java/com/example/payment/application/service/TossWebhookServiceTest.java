package com.example.payment.application.service;

import com.example.payment.domain.entity.PaymentRecord;
import com.example.payment.domain.entity.TossWebhookEvent;
import com.example.payment.domain.repository.PaymentRecordRepository;
import com.example.payment.domain.repository.TossWebhookEventRepository;
import com.example.payment.infrastructure.gateway.TossPaymentsGateway;
import com.example.payment.infrastructure.gateway.TossPaymentsProperties;
import com.example.payment.presentation.dto.response.CompleteReservationResponse;
import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TossWebhookServiceTest {

    private final TossWebhookEventRepository webhookRepository = mock(TossWebhookEventRepository.class);
    private final PaymentRecordRepository paymentRepository = mock(PaymentRecordRepository.class);
    private final TossPaymentIntentService intentService = mock(TossPaymentIntentService.class);
    private final MarketplaceOrderService marketplaceOrderService = mock(MarketplaceOrderService.class);
    private final TossPaymentsGateway gateway = mock(TossPaymentsGateway.class);
    private final TossPaymentsProperties properties = new TossPaymentsProperties();
    private final RefundLedgerWriter refunds = mock(RefundLedgerWriter.class);
    private final RefundRecoveryService recovery = mock(RefundRecoveryService.class);
    private TossWebhookService service;

    @BeforeEach
    void setUp() {
        properties.getWebhook().setEnabled(true);
        properties.getWebhook().setPathToken("abcdefghijklmnopqrstuvwxyz123456");
        properties.getWebhook().setMaxRetry(7);
        when(webhookRepository.claim(any(), any(), any())).thenReturn(1);
        service = new TossWebhookService(
                webhookRepository,
                paymentRepository,
                intentService,
                marketplaceOrderService,
                gateway,
                properties,
                new ObjectMapper(),
                new SimpleMeterRegistry(),
                refunds,
                recovery
        );
    }

    @Test
    void receiveStoresWebhookEventIdempotently() {
        String payload = """
                {
                  "eventId": "evt_1",
                  "eventType": "PAYMENT_STATUS_CHANGED",
                  "data": {
                    "paymentKey": "pay_1",
                    "orderId": "ORD-1",
                    "status": "DONE"
                  }
                }
                """;
        when(webhookRepository.findByDedupeKey("PAYMENT_STATUS_CHANGED:evt_1")).thenReturn(Optional.empty());
        when(webhookRepository.save(any(TossWebhookEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TossWebhookService.WebhookReceipt receipt = service.receive("abcdefghijklmnopqrstuvwxyz123456", payload);

        assertEquals("PENDING", receipt.processingStatus());
        verify(webhookRepository).save(any(TossWebhookEvent.class));
    }

    @Test
    void receiveRejectsInvalidToken() {
        assertThrows(ResponseStatusException.class, () -> service.receive("wrong", "{}"));
    }

    @Test
    void processDoneWebhookRecoversTossIntent() {
        TossWebhookEvent event = event("PAYMENT_STATUS_CHANGED", "DONE");
        when(gateway.getPaymentStatus("pay_1")).thenReturn(com.example.payment.application.dto.PaymentGatewayResult.builder()
                .success(true).gatewayStatus("APPROVED").transactionId("pay_1").orderId("ORD-1").build());
        when(intentService.recoverIntentByProviderReference("pay_1", "ORD-1"))
                .thenReturn(CompleteReservationResponse.builder()
                        .status("SUCCESS")
                        .workflowId("WF-1")
                        .build());

        service.processEvent(event);

        verify(webhookRepository).finish(org.mockito.ArgumentMatchers.eq("TOSS-WH-1"), any(),
                org.mockito.ArgumentMatchers.eq("SUCCEEDED"), org.mockito.ArgumentMatchers.isNull(), any());
        verify(intentService).recoverIntentByProviderReference("pay_1", "ORD-1");
    }

    @Test
    void processCancelWebhookUpdatesPaymentAsPartiallyRefunded() {
        TossWebhookEvent event = event("CANCEL_STATUS_CHANGED", "PARTIAL_CANCELED");
        var observation = com.example.payment.application.dto.PaymentGatewayResult.builder()
                .gatewayStatus("PARTIALLY_REFUNDED").transactionId("pay_1").orderId("ORD-1").build();
        when(gateway.getPaymentStatus("pay_1")).thenReturn(observation);
        PaymentRecord payment = PaymentRecord.builder()
                .paymentId("PAY-1")
                .orderId("ORD-1")
                .reservationId("RES-1")
                .customerId("CUST-1")
                .amount(new BigDecimal("10000.00"))
                .currency("KRW")
                .method("CREDIT_CARD")
                .status("APPROVED")
                .transactionId("pay_1")
                .createdAt(LocalDateTime.now())
                .build();
        when(paymentRepository.findByTransactionId("pay_1")).thenReturn(Optional.of(payment));

        service.processEvent(event);

        verify(refunds).observe("PAY-1", observation, null, false);
        verify(recovery).recoverPayment("PAY-1");
        org.mockito.Mockito.verify(paymentRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test void unverifiedCancelWebhookCannotInventRefund() {
        when(gateway.getPaymentStatus("pay_1")).thenReturn(com.example.payment.application.dto.PaymentGatewayResult.unknown("timeout"));
        service.processEvent(event("CANCEL_STATUS_CHANGED", "CANCELED"));
        org.mockito.Mockito.verifyNoInteractions(refunds, recovery);
        verify(webhookRepository).finish(org.mockito.ArgumentMatchers.eq("TOSS-WH-1"), any(),
                org.mockito.ArgumentMatchers.eq("FAILED"), any(), any());
    }

    private TossWebhookEvent event(String eventType, String status) {
        return TossWebhookEvent.builder()
                .eventId("TOSS-WH-1")
                .dedupeKey(eventType + ":evt_1")
                .eventType(eventType)
                .paymentKey("pay_1")
                .orderId("ORD-1")
                .paymentStatus(status)
                .rawPayload("""
                        {
                          "eventId": "evt_1",
                          "eventType": "%s",
                          "data": {
                            "paymentKey": "pay_1",
                            "orderId": "ORD-1",
                            "status": "%s"
                          }
                        }
                        """.formatted(eventType, status))
                .processingStatus("PENDING")
                .attemptCount(0)
                .receivedAt(LocalDateTime.now())
                .build();
    }
}

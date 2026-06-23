package com.example.payment.application.service;

import com.example.payment.domain.model.common.Money;
import com.example.payment.domain.entity.PaymentRecord;
import com.example.payment.domain.model.payment.Payment;
import com.example.payment.domain.model.payment.PaymentMethod;
import com.example.payment.domain.model.payment.PaymentStatus;
import com.example.payment.domain.repository.PaymentRecordRepository;
import com.example.payment.domain.repository.RefundRecordRepository;
import com.example.payment.domain.entity.RefundRecord;
import com.example.payment.domain.service.PaymentGatewayService;
import com.example.payment.infrastructure.messaging.outbox.OutboxEventService;
import com.example.payment.infrastructure.gateway.PaymentGatewayFactory;
import com.example.payment.infrastructure.persistence.redis.repository.CacheService;
import com.example.payment.presentation.dto.response.PaymentResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class PaymentProcessingServiceTest {

    @Test
    void getPaymentStatus_preservesCurrencyAndMapsFailureReasonToMessage() {
        PaymentGatewayFactory gatewayFactory = mock(PaymentGatewayFactory.class);
        CacheService cacheService = mock(CacheService.class);
        PaymentRecordRepository paymentRecordRepository = mock(PaymentRecordRepository.class);
        PaymentProcessingService service = new PaymentProcessingService(
                gatewayFactory,
                cacheService,
                paymentRecordRepository,
                mock(RefundRecordRepository.class),
                mock(OutboxEventService.class)
        );

        Payment payment = Payment.builder()
                .paymentId("PAY-1")
                .orderId("ORD-1")
                .reservationId("RES-1")
                .customerId("CUS-1")
                .amount(Money.of(new BigDecimal("100.00"), "KRW"))
                .method(PaymentMethod.CREDIT_CARD)
                .status(PaymentStatus.FAILED)
                .failureReason("declined")
                .build();

        when(cacheService.getCachedObject("payment:PAY-1", Payment.class)).thenReturn(payment);

        PaymentResponse response = service.getPaymentStatus("PAY-1");

        assertEquals("KRW", response.getCurrency());
        assertEquals("declined", response.getMessage());
    }

    @Test
    void refundPaymentWithResult_recordsRefundLedgerAndOutbox() {
        PaymentGatewayFactory gatewayFactory = mock(PaymentGatewayFactory.class);
        CacheService cacheService = mock(CacheService.class);
        PaymentRecordRepository paymentRecordRepository = mock(PaymentRecordRepository.class);
        RefundRecordRepository refundRecordRepository = mock(RefundRecordRepository.class);
        OutboxEventService outboxEventService = mock(OutboxEventService.class);
        PaymentGatewayService gateway = mock(PaymentGatewayService.class);
        PaymentProcessingService service = new PaymentProcessingService(
                gatewayFactory,
                cacheService,
                paymentRecordRepository,
                refundRecordRepository,
                outboxEventService
        );

        PaymentRecord record = PaymentRecord.builder()
                .paymentId("PAY-1")
                .orderId("ORD-1")
                .reservationId("RES-1")
                .customerId("CUS-1")
                .amount(new BigDecimal("100.00"))
                .currency("KRW")
                .method("CREDIT_CARD")
                .status("COMPLETED")
                .transactionId("pay_test_key")
                .gatewayName("TOSS_PAYMENTS")
                .processedAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .build();

        when(paymentRecordRepository.findById("PAY-1")).thenReturn(Optional.of(record));
        when(refundRecordRepository.findByPaymentIdAndIdempotencyKey("PAY-1", "REF-1")).thenReturn(Optional.empty());
        when(refundRecordRepository.save(any(RefundRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(refundRecordRepository.sumSucceededAmountByPaymentId("PAY-1")).thenReturn(new BigDecimal("100.00"));
        when(gatewayFactory.getGateway("CREDIT_CARD")).thenReturn(gateway);
        when(gateway.refundPayment("pay_test_key")).thenReturn(true);

        PaymentProcessingService.RefundResult result = service.refundPaymentWithResult("PAY-1", "REF-1", "customer request");

        assertEquals(true, result.success());
        assertEquals("REFUND_SUCCEEDED", result.code());
        assertEquals("REFUNDED", record.getStatus());
        verify(gateway).refundPayment("pay_test_key");
        verify(outboxEventService).record(
                eq("PAYMENT"),
                eq("PAY-1"),
                eq("PAYMENT_REFUNDED"),
                eq("payment-events"),
                eq("PAY-1"),
                anyMap()
        );
    }

    @Test
    void getPaymentStatusByReservationId_mapsPaymentRecordToResponse() {
        PaymentGatewayFactory gatewayFactory = mock(PaymentGatewayFactory.class);
        CacheService cacheService = mock(CacheService.class);
        PaymentRecordRepository paymentRecordRepository = mock(PaymentRecordRepository.class);
        PaymentProcessingService service = new PaymentProcessingService(
                gatewayFactory,
                cacheService,
                paymentRecordRepository,
                mock(RefundRecordRepository.class),
                mock(OutboxEventService.class)
        );

        PaymentRecord record = PaymentRecord.builder()
                .paymentId("PAY-1")
                .orderId("ORD-1")
                .reservationId("RES-1")
                .customerId("CUS-1")
                .amount(new BigDecimal("100.00"))
                .currency("KRW")
                .method("CREDIT_CARD")
                .status("COMPLETED")
                .transactionId("TX-1")
                .approvalNumber("APP-1")
                .gatewayName("MOCK_PAYMENT_GATEWAY")
                .processedAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .build();

        when(paymentRecordRepository.findByReservationId("RES-1")).thenReturn(Optional.of(record));

        PaymentResponse response = service.getPaymentStatusByReservationId("RES-1");

        assertNotNull(response);
        assertEquals("PAY-1", response.getPaymentId());
        assertEquals("COMPLETED", response.getStatus());
        assertEquals("KRW", response.getCurrency());
    }
}

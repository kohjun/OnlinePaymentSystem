package com.example.payment.application.service;

import com.example.payment.domain.model.common.Money;
import com.example.payment.domain.model.payment.Payment;
import com.example.payment.domain.model.payment.PaymentMethod;
import com.example.payment.domain.model.payment.PaymentStatus;
import com.example.payment.domain.repository.PaymentRecordRepository;
import com.example.payment.infrastructure.gateway.PaymentGatewayFactory;
import com.example.payment.infrastructure.persistence.redis.repository.CacheService;
import com.example.payment.infrastructure.persistence.wal.WalService;
import com.example.payment.presentation.dto.response.PaymentResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentProcessingServiceTest {

    @Test
    void getPaymentStatus_preservesCurrencyAndMapsFailureReasonToMessage() {
        PaymentGatewayFactory gatewayFactory = mock(PaymentGatewayFactory.class);
        CacheService cacheService = mock(CacheService.class);
        WalService walService = mock(WalService.class);
        PaymentRecordRepository paymentRecordRepository = mock(PaymentRecordRepository.class);
        PaymentProcessingService service = new PaymentProcessingService(
                gatewayFactory,
                cacheService,
                walService,
                paymentRecordRepository
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
}

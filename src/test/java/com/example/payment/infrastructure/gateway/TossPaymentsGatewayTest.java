package com.example.payment.infrastructure.gateway;

import com.example.payment.application.dto.PaymentGatewayRequest;
import com.example.payment.application.dto.PaymentGatewayResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TossPaymentsGatewayTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private TossPaymentsGateway gateway;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        TossPaymentsProperties properties = new TossPaymentsProperties();
        properties.setClientKey("test_ck_123");
        properties.setSecretKey("test_sk_123");
        properties.setBaseUrl("https://api.tosspayments.com");
        properties.setMode("test");
        gateway = new TossPaymentsGateway(restTemplate, new ObjectMapper(), properties);
    }

    @Test
    void authorizeConfirmsTossPayment() {
        server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "PAY-1"))
                .andRespond(withSuccess("""
                        {
                          "paymentKey": "pay_test_key",
                          "orderId": "ORD-1",
                          "status": "DONE",
                          "totalAmount": 15000,
                          "currency": "KRW",
                          "approvedAt": "2026-06-22T10:00:00+09:00",
                          "card": {"approveNo": "12345678"}
                        }
                        """, MediaType.APPLICATION_JSON));

        PaymentGatewayResult result = gateway.authorize(PaymentGatewayRequest.builder()
                .paymentId("PAY-1")
                .idempotencyKey("PAY-1")
                .customerId("CUS-1")
                .amount(new BigDecimal("15000"))
                .currency("KRW")
                .method("CREDIT_CARD")
                .tossPaymentKey("pay_test_key")
                .tossOrderId("ORD-1")
                .build());

        assertTrue(result.isSuccess());
        assertEquals("APPROVED", result.getGatewayStatus());
        assertEquals("pay_test_key", result.getTransactionId());
        assertEquals("12345678", result.getApprovalNumber());
        server.verify();
    }

    @Test
    void authorizeReturnsFailureForTossBusinessError() {
        server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withBadRequest().body("""
                        {"code":"REJECT_CARD_COMPANY","message":"Card rejected"}
                        """).contentType(MediaType.APPLICATION_JSON));

        PaymentGatewayResult result = gateway.authorize(PaymentGatewayRequest.builder()
                .paymentId("PAY-2")
                .idempotencyKey("PAY-2")
                .customerId("CUS-1")
                .amount(new BigDecimal("15000"))
                .currency("KRW")
                .method("CREDIT_CARD")
                .tossPaymentKey("pay_test_key")
                .tossOrderId("ORD-2")
                .build());

        assertFalse(result.isSuccess());
        assertEquals("FAILED", result.getGatewayStatus());
        assertEquals("REJECT_CARD_COMPANY", result.getErrorCode());
        server.verify();
    }

    @Test
    void refundCancelsByPaymentKey() {
        server.expect(requestTo("https://api.tosspayments.com/v1/payments/pay_test_key/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "refund-pay_test_key"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertTrue(gateway.refundPayment("pay_test_key"));
        server.verify();
    }
}

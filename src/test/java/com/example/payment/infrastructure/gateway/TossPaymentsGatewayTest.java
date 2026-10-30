package com.example.payment.infrastructure.gateway;

import com.example.payment.application.dto.PaymentGatewayRequest;
import com.example.payment.application.dto.PaymentGatewayResult;
import com.example.payment.domain.exception.PaymentGatewayResultUnknownException;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
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
        // approvedAt +09:00 10시는 UTC 01시로 저장해야 다른 UTC 시각과 비교된다.
        assertEquals(java.time.LocalDateTime.of(2026, 6, 22, 1, 0), result.getProcessedAt());
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
                .andRespond(withSuccess("{\"status\":\"CANCELED\",\"paymentKey\":\"pay_test_key\"}", MediaType.APPLICATION_JSON));

        assertTrue(gateway.refundPayment("pay_test_key"));
        server.verify();
    }

    @Test
    void refundRaisesUnknownWhenConnectionTimesOut() {
        server.expect(requestTo("https://api.tosspayments.com/v1/payments/pay_test_key/cancel"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThrows(PaymentGatewayResultUnknownException.class,
                () -> gateway.refundPayment("pay_test_key"));
        server.verify();
    }

    @Test
    void statusMapsPartialCanceledToPartiallyRefunded() {
        server.expect(requestTo("https://api.tosspayments.com/v1/payments/pay_test_key"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "paymentKey": "pay_test_key",
                          "orderId": "ORD-1",
                          "status": "PARTIAL_CANCELED",
                          "totalAmount": 15000,
                          "currency": "KRW"
                        }
                        """, MediaType.APPLICATION_JSON));

        PaymentGatewayResult result = gateway.getPaymentStatus("pay_test_key");

        assertEquals("PARTIALLY_REFUNDED", result.getGatewayStatus());
        server.verify();
    }

    @Test
    void authorizeReturnsUnknownWhenConnectionTimesOut() {
        server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        PaymentGatewayResult result = gateway.authorize(paymentRequest("PAY-TIMEOUT"));

        assertFalse(result.isSuccess());
        assertEquals("UNKNOWN", result.getGatewayStatus());
        assertEquals("TOSS_NETWORK_ERROR", result.getErrorCode());
        server.verify();
    }

    @Test
    void authorizeReturnsUnknownForProviderServerError() {
        server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
                .andRespond(withServerError());

        PaymentGatewayResult result = gateway.authorize(paymentRequest("PAY-500"));

        assertFalse(result.isSuccess());
        assertEquals("UNKNOWN", result.getGatewayStatus());
        assertEquals("TOSS_HTTP_500", result.getErrorCode());
        server.verify();
    }

    @Test
    void rejectsInternalMockPaymentMethods() {
        assertFalse(gateway.supports("MOCK"));
        assertFalse(gateway.supports("TEST"));
        assertTrue(gateway.supports("CREDIT_CARD"));
    }

    private PaymentGatewayRequest paymentRequest(String paymentId) {
        return PaymentGatewayRequest.builder()
                .paymentId(paymentId)
                .idempotencyKey(paymentId)
                .customerId("CUS-1")
                .amount(new BigDecimal("15000"))
                .currency("KRW")
                .method("CREDIT_CARD")
                .tossPaymentKey("pay_test_key")
                .tossOrderId("ORD-" + paymentId)
                .build();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {401, 404, 409, 429, 500})
    void lookupErrorsAreNeverEvidenceOfPaymentRejection(int status) {
        server.expect(requestTo("https://api.tosspayments.com/v1/payments/pay_test_key"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(
                        org.springframework.http.HttpStatus.valueOf(status)));
        assertEquals("UNKNOWN", gateway.getPaymentStatus("pay_test_key").getGatewayStatus());
        server.verify();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"ALREADY_PROCESSED_PAYMENT", "ALREADY_PROCESSING_REQUEST", "PROVIDER_ERROR", "INVALID_API_KEY"})
    void ambiguous400ApprovalIsNotRejection(String code) {
        server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
                .andRespond(withBadRequest().body("{\"code\":\"" + code + "\"}").contentType(MediaType.APPLICATION_JSON));
        assertEquals("UNKNOWN", gateway.authorize(paymentRequest("P")).getGatewayStatus());
        server.verify();
    }

    @Test void refundServerErrorIsUnknownNotFailed() {
        server.expect(requestTo("https://api.tosspayments.com/v1/payments/pay_test_key/cancel"))
                .andRespond(withServerError());
        var result = gateway.refund(com.example.payment.application.dto.PaymentRefundRequest.builder()
                .transactionId("pay_test_key").idempotencyKey("refund-1").amount(new BigDecimal("10000")).reason("cancel").build());
        assertEquals(com.example.payment.application.dto.PaymentRefundResult.Status.UNKNOWN, result.status());
        server.verify();
    }
}

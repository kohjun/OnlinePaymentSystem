package com.example.payment.infrastructure.gateway;

import com.example.payment.application.service.SellerPayoutTransferGateway.TransferRequest;
import com.example.payment.application.service.SellerPayoutTransferGateway.TransferResult;
import com.example.payment.domain.model.marketplace.PayoutTransferStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TossPayoutsSellerPayoutTransferGatewayTest {

    private static final String BASE_URL = "https://payouts.example.com";
    private static final String IDEMPOTENCY_KEY = "seller-payout:PAYOUT-1";

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private TossPayoutsSellerPayoutTransferGateway gateway;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);

        TossPayoutsProperties properties = new TossPayoutsProperties();
        properties.setSecretKey("test_sk_payout");
        properties.setBaseUrl(BASE_URL);
        properties.setMode("test");

        gateway = new TossPayoutsSellerPayoutTransferGateway(restTemplate, new ObjectMapper(), properties);
    }

    private TransferRequest request() {
        return new TransferRequest(
                "PAYOUT-1",
                "SELLER-1",
                "vault://account/1",
                new BigDecimal("90000.00"),
                "KRW",
                IDEMPOTENCY_KEY
        );
    }

    @Test
    @DisplayName("제공자 이름이 조건부 프로퍼티 값과 일치해야 준비도 검사가 통과한다")
    void providerNameMatchesConditionalPropertyValue() {
        assertEquals("TOSS_PAYOUTS", gateway.providerName());
    }

    @Test
    @DisplayName("송금 성공은 제공자 송금 식별자와 함께 SUCCEEDED로 매핑된다")
    void successfulTransferCarriesProviderTransferId() {
        server.expect(requestTo(BASE_URL + "/v1/payouts"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andRespond(withSuccess("""
                        { "payoutId": "PO-9001", "status": "COMPLETED" }
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        TransferResult result = gateway.transfer(request());

        assertEquals(PayoutTransferStatus.SUCCEEDED, result.status());
        assertEquals("PO-9001", result.providerTransferId());
        assertNull(result.failureReason());
        server.verify();
    }

    @Test
    @DisplayName("진행 중 응답은 PROCESSING으로 남아 재송금 대상이 되지 않는다")
    void inProgressTransferIsProcessing() {
        server.expect(requestTo(BASE_URL + "/v1/payouts"))
                .andRespond(withSuccess("""
                        { "payoutId": "PO-9002", "status": "IN_PROGRESS" }
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        TransferResult result = gateway.transfer(request());

        assertEquals(PayoutTransferStatus.PROCESSING, result.status());
        assertEquals("PO-9002", result.providerTransferId());
    }

    @Test
    @DisplayName("4xx는 종결된 실패이므로 FAILED로 분류한다")
    void clientErrorIsTerminalFailure() {
        server.expect(requestTo(BASE_URL + "/v1/payouts"))
                .andRespond(withBadRequest()
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .body("""
                                { "code": "INVALID_ACCOUNT", "message": "account is not payable" }
                                """));

        TransferResult result = gateway.transfer(request());

        assertEquals(PayoutTransferStatus.FAILED, result.status());
        assertTrue(result.failureReason().contains("INVALID_ACCOUNT"));
    }

    @Test
    @DisplayName("5xx는 결과 미상이므로 UNKNOWN으로 두어 재송금을 막는다")
    void serverErrorIsIndeterminate() {
        server.expect(requestTo(BASE_URL + "/v1/payouts"))
                .andRespond(withServerError());

        TransferResult result = gateway.transfer(request());

        assertEquals(PayoutTransferStatus.UNKNOWN, result.status());
    }

    @Test
    @DisplayName("429는 실패가 아니라 결과 미상으로 분류한다")
    void tooManyRequestsIsIndeterminate() {
        server.expect(requestTo(BASE_URL + "/v1/payouts"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS));

        assertEquals(PayoutTransferStatus.UNKNOWN, gateway.transfer(request()).status());
    }

    @Test
    @DisplayName("타임아웃은 송금 도달 여부를 알 수 없으므로 UNKNOWN이다")
    void networkTimeoutIsIndeterminate() {
        server.expect(requestTo(BASE_URL + "/v1/payouts"))
                .andRespond(withException(new SocketTimeoutException("read timed out")));

        TransferResult result = gateway.transfer(request());

        assertEquals(PayoutTransferStatus.UNKNOWN, result.status());
        assertNull(result.providerTransferId());
    }

    @Test
    @DisplayName("식별자 없는 성공 응답은 SUCCEEDED로 올리지 않는다")
    void successWithoutTransferIdIsDowngraded() {
        server.expect(requestTo(BASE_URL + "/v1/payouts"))
                .andRespond(withSuccess("""
                        { "status": "COMPLETED" }
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        TransferResult result = gateway.transfer(request());

        assertEquals(PayoutTransferStatus.UNKNOWN, result.status());
    }

    @Test
    @DisplayName("상태 조회는 제공자 송금 식별자로 단건 조회한다")
    void statusLookupUsesProviderTransferId() {
        server.expect(requestTo(BASE_URL + "/v1/payouts/PO-9001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        { "payoutId": "PO-9001", "status": "DONE" }
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        TransferResult result = gateway.getStatus("PO-9001", IDEMPOTENCY_KEY);

        assertEquals(PayoutTransferStatus.SUCCEEDED, result.status());
        assertEquals("PO-9001", result.providerTransferId());
        server.verify();
    }

    @Test
    @DisplayName("식별자를 받기 전 UNKNOWN이 된 송금은 멱등키로 조회한다")
    void statusLookupFallsBackToIdempotencyKey() {
        server.expect(requestTo(BASE_URL + "/v1/payouts?refPayoutId=" + IDEMPOTENCY_KEY))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [ { "payoutId": "PO-9003", "status": "COMPLETED" } ]
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        TransferResult result = gateway.getStatus(null, IDEMPOTENCY_KEY);

        assertEquals(PayoutTransferStatus.SUCCEEDED, result.status());
        assertEquals("PO-9003", result.providerTransferId());
        server.verify();
    }

    @Test
    @DisplayName("조회에서의 404는 아직 미생성일 수 있으므로 실패로 단정하지 않는다")
    void notFoundOnLookupStaysUnknown() {
        server.expect(requestTo(BASE_URL + "/v1/payouts/PO-9004"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

        TransferResult result = gateway.getStatus("PO-9004", IDEMPOTENCY_KEY);

        assertEquals(PayoutTransferStatus.UNKNOWN, result.status());
        assertEquals("PO-9004", result.providerTransferId());
    }

    @Test
    @DisplayName("빈 목록 조회 결과도 UNKNOWN으로 남긴다")
    void emptyLookupResultStaysUnknown() {
        server.expect(requestTo(BASE_URL + "/v1/payouts?refPayoutId=" + IDEMPOTENCY_KEY))
                .andRespond(withSuccess("[]", org.springframework.http.MediaType.APPLICATION_JSON));

        assertEquals(PayoutTransferStatus.UNKNOWN, gateway.getStatus(null, IDEMPOTENCY_KEY).status());
    }

    @Test
    @DisplayName("조회 키가 전혀 없으면 요청을 보내지 않는다")
    void missingLookupKeysDoNotCallProvider() {
        TransferResult result = gateway.getStatus(null, null);

        assertEquals(PayoutTransferStatus.UNKNOWN, result.status());
        server.verify(); // 기대한 요청이 없으므로 호출이 없어야 통과한다
    }

    @Test
    @DisplayName("실패 사유에 계좌 식별자와 긴 숫자가 남지 않는다")
    void failureReasonIsMasked() {
        server.expect(requestTo(BASE_URL + "/v1/payouts"))
                .andRespond(withBadRequest()
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .body("""
                                { "code": "INVALID_ACCOUNT",
                                  "message": "accountRef=vault://account/1 number 110234567890 rejected" }
                                """));

        String reason = gateway.transfer(request()).failureReason();

        assertFalse(reason.contains("vault://account/1"));
        assertFalse(reason.contains("110234567890"));
        assertTrue(reason.contains("INVALID_ACCOUNT"));
    }

    @Test
    @DisplayName("파싱할 수 없는 응답은 UNKNOWN으로 둔다")
    void unparseableResponseStaysUnknown() throws IOException {
        server.expect(requestTo(BASE_URL + "/v1/payouts"))
                .andRespond(withSuccess("not-json", org.springframework.http.MediaType.APPLICATION_JSON));

        assertEquals(PayoutTransferStatus.UNKNOWN, gateway.transfer(request()).status());
    }
}

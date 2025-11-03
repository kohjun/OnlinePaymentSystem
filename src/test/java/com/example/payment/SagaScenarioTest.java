package com.example.payment;

import com.example.payment.application.dto.PaymentGatewayResult;
import com.example.payment.application.service.PaymentProcessingService;
import com.example.payment.domain.service.PaymentGatewayService;
import com.example.payment.infrastructure.gateway.MockPaymentGateway;
import com.example.payment.infrastructure.util.ResourceReservationService;
import com.example.payment.presentation.dto.request.CompleteReservationRequest;
import com.example.payment.presentation.dto.response.CompleteReservationResponse;
import com.example.payment.presentation.dto.response.ReservationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;

/**
 * Saga 시나리오 테스트 (보상 트랜잭션 검증)
 * 1. 결제 실패 시 -> 재고 롤백 검증
 * 2. 예약 성공 후 취소 시 -> 재고 롤백 및 환불 검증
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Transactional // 테스트 후 DB 롤백 (JPA 데이터)
class SagaScenarioTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ResourceReservationService redisReservationService;

    // [핵심] MockPaymentGateway를 MockBean으로 대체하여 강제로 결제 실패/성공을 제어
    @MockBean
    private MockPaymentGateway mockPaymentGateway;

    private static final String PRODUCT_ID_SAGA = "SAGA-TEST-001";
    private static final int SAGA_STOCK = 5;

    @BeforeEach
    void setUp() {
        // 1. 테스트용 재고를 Redis에 초기화 (Lua 스크립트가 인식하는 형식)
        redisReservationService.initializeResource(
                "inventory:" + PRODUCT_ID_SAGA,
                SAGA_STOCK, // total
                SAGA_STOCK  // available
        );

        // 2. MockGateway가 기본적으로 성공하도록 설정
        // (MockPaymentGateway의 10% 실패 확률을 제거하여 테스트 안정성 확보)
        Mockito.when(mockPaymentGateway.processPayment(any()))
                .thenReturn(PaymentGatewayResult.builder()
                        .success(true)
                        .transactionId("MOCK_TX_SUCCESS")
                        .approvalNumber("MOCK_APPROVAL_123")
                        .processedAmount(new BigDecimal("100.00"))
                        .build());

        // 3. MockGateway의 환불도 성공하도록 설정
        Mockito.when(mockPaymentGateway.refundPayment(any()))
                .thenReturn(true);
    }

    @Test
    @DisplayName("[실패 시나리오] 결제가 실패하면 Saga 보상 트랜잭션이 동작하여 재고가 롤백된다")
    void test_whenPaymentFails_thenSagaIsCompensated() {
        // 1. [Given] PG(결제)가 실패하도록 Mock을 설정
        Mockito.when(mockPaymentGateway.processPayment(any()))
                .thenReturn(PaymentGatewayResult.failure("MOCK_FAILURE", "의도된 결제 실패"));

        // 2. [When] 통합 예약을 시도
        String url = "http://localhost:" + port + "/api/reservations/complete";
        CompleteReservationRequest request = createReservationRequest(PRODUCT_ID_SAGA, "CUS-FAIL-001");

        ResponseEntity<CompleteReservationResponse> response =
                restTemplate.postForEntity(url, request, CompleteReservationResponse.class);

        // 3. [Then] API 응답이 FAILED인지 확인
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("FAILED", response.getBody().getStatus());
        assertEquals("결제 실패: 의도된 결제 실패", response.getBody().getMessage());

        // 4. [검증] 재고가 롤백되었는지 확인 (가장 중요)
        // 재고(5개)가 선점되었다가(available: 4) 결제 실패로 롤백되어(available: 5)
        // available 수량이 5개여야 합니다.
        Map<String, Object> inventory = redisReservationService.getResourceStatus("inventory:" + PRODUCT_ID_SAGA);
        assertEquals(SAGA_STOCK, inventory.get("available")); // available 롤백 확인
        assertEquals(0, inventory.get("reserved")); // reserved 롤백 확인

        System.out.println("✅ [실패 테스트] 결제 실패 시 재고가 정상적으로 롤백되었습니다.");
    }

    @Test
    @DisplayName("[취소 시나리오] 예약 성공 후 취소 API를 호출하면 재고가 롤백되고 환불이 호출된다")
    void test_whenReservationIsCancelled_thenOrderAndPaymentAreCancelled() {
        // 1. [Given] 통합 예약을 성공시킴
        String url = "http://localhost:" + port + "/api/reservations/complete";
        String customerId = "CUS-CANCEL-001";
        CompleteReservationRequest request = createReservationRequest(PRODUCT_ID_SAGA, customerId);

        ResponseEntity<CompleteReservationResponse> successResponse =
                restTemplate.postForEntity(url, request, CompleteReservationResponse.class);

        assertEquals(HttpStatus.OK, successResponse.getStatusCode());
        assertEquals("SUCCESS", successResponse.getBody().getStatus());
        String reservationId = successResponse.getBody().getReservation().getReservationId();
        assertNotNull(reservationId);

        // 1.1. 재고가 1개 차감되었는지 확인 (available: 4, reserved: 0 <- confirm까지 완료됨)
        Map<String, Object> inventoryAfterSuccess = redisReservationService.getResourceStatus("inventory:" + PRODUCT_ID_SAGA);
        assertEquals(SAGA_STOCK - 1, inventoryAfterSuccess.get("available"));
        assertEquals(0, inventoryAfterSuccess.get("reserved")); // confirm에서 0이 됨

        // 2. [When] 예약 취소(환불) API 호출
        String cancelUrl = "http://localhost:" + port + "/api/reservations/" + reservationId +
                "?customerId=" + customerId + "&reason=TestCancel";

        ResponseEntity<String> cancelResponse =
                restTemplate.exchange(cancelUrl, HttpMethod.DELETE, null, String.class);

        // 3. [Then] 취소 API 응답 확인
        assertEquals(HttpStatus.OK, cancelResponse.getStatusCode());
        System.out.println("✅ [취소 테스트] 취소 API 호출 성공");

        // 4. [검증] PG 환불이 호출되었는지 Mockito로 검증
        Mockito.verify(mockPaymentGateway, Mockito.times(1)).refundPayment(any());
        System.out.println("✅ [취소 테스트] PG 환불(refundPayment)이 호출되었습니다.");

        // 5. [검증] 재고가 롤백되었는지 확인 (가장 중요)
        // (available: 4) -> (취소 롤백) -> (available: 5)
        Map<String, Object> inventoryAfterCancel = redisReservationService.getResourceStatus("inventory:" + PRODUCT_ID_SAGA);
        assertEquals(SAGA_STOCK, inventoryAfterCancel.get("available")); // available 롤백 확인
        System.out.println("✅ [취소 테스트] 재고가 정상적으로 롤백되었습니다.");
    }


    private CompleteReservationRequest createReservationRequest(String productId, String customerId) {
        return CompleteReservationRequest.builder()
                .productId(productId)
                .customerId(customerId)
                .quantity(1)
                .clientId("saga-test-client")
                .paymentInfo(CompleteReservationRequest.PaymentInfo.builder()
                        .amount(new BigDecimal("100.00"))
                        .currency("KRW")
                        .paymentMethod("CREDIT_CARD")
                        .build())
                .idempotencyKey(UUID.randomUUID().toString())
                .correlationId(UUID.randomUUID().toString())
                .build();
    }
}
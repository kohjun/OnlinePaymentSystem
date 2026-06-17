package com.example.payment;

import com.example.payment.application.dto.PaymentGatewayResult;
import com.example.payment.application.service.InventoryManagementService;
import com.example.payment.application.service.OrderService;
import com.example.payment.domain.model.inventory.Reservation;
import com.example.payment.domain.repository.ReservationRepository;
import com.example.payment.infrastructure.gateway.MockPaymentGateway;
import com.example.payment.infrastructure.util.ResourceReservationService;
import com.example.payment.presentation.dto.request.CompleteReservationRequest;
import com.example.payment.presentation.dto.response.CompleteReservationResponse;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 2 실패 시나리오 테스트 (WAL 제거)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Transactional
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class Phase2FailureScenarioTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ResourceReservationService redisReservationService;

    @Autowired
    private ReservationRepository reservationRepository;

    @MockBean
    private MockPaymentGateway mockPaymentGateway;

    // SpyBean을 사용하여 실제 메서드는 호출되지만 특정 메서드만 Mock 가능
    @SpyBean
    private InventoryManagementService inventoryManagementService;

    @SpyBean
    private OrderService orderService;

    private static final String TEST_PRODUCT_ID = "PHASE2-TEST-001";
    private static final int INITIAL_STOCK = 10;

    @BeforeEach
    void setUp() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("⚠️ Phase 2 Failure Scenario Test Setup");
        System.out.println("=".repeat(70));

        // 1. Redis 재고 초기화
        redisReservationService.initializeResource(
                "inventory:" + TEST_PRODUCT_ID,
                INITIAL_STOCK,
                INITIAL_STOCK
        );

        // 2. MockGateway 설정 (결제는 성공)
        when(mockPaymentGateway.processPayment(any()))
                .thenReturn(PaymentGatewayResult.builder()
                        .success(true)
                        .transactionId("MOCK_TX_PHASE2_TEST")
                        .approvalNumber("MOCK_APPROVAL_PHASE2")
                        .processedAmount(new BigDecimal("100.00"))
                        .build());
        when(mockPaymentGateway.refundPayment(any())).thenReturn(true);
        when(mockPaymentGateway.getGatewayName()).thenReturn("MOCK_PAYMENT_GATEWAY");

        System.out.println("✅ Setup 완료: Redis 재고 초기화, MockGateway 설정");
    }

    @Test
    @DisplayName("[Phase 2 실패 1] 결제 실패 시 보상 트랜잭션 동작 (대체 시나리오)")
    void test_paymentFailure_triggersCompensation() {
        System.out.println("\n[테스트 1] 결제 실패 시 보상 트랜잭션 (Phase 2 대체)");

        // [Given] 결제가 실패하도록 설정 (이것은 Phase 1 실패이지만 보상 트랜잭션 검증에 유효)
        when(mockPaymentGateway.processPayment(any()))
                .thenReturn(PaymentGatewayResult.failure("MOCK_FAILURE", "의도된 결제 실패"));

        // [When] 예약 시도
        String customerId = "PHASE2-CUSTOMER-001";
        CompleteReservationRequest request = createReservationRequest(TEST_PRODUCT_ID, customerId);
        String correlationId = request.getCorrelationId();
        String url = "http://localhost:" + port + "/api/reservations/complete";

        ResponseEntity<CompleteReservationResponse> response =
                restTemplate.postForEntity(url, request, CompleteReservationResponse.class);

        // [Then] API 응답 검증 (실패 응답)
        System.out.println("\n📊 API 응답:");
        System.out.println("  - Status Code: " + response.getStatusCode());
        System.out.println("  - Body Status: " + (response.getBody() != null ? response.getBody().getStatus() : "null"));
        System.out.println("  - Message: " + (response.getBody() != null ? response.getBody().getMessage() : "null"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("FAILED", response.getBody().getStatus());

        System.out.println("✅ API 실패 응답 확인");

        // [검증 1] Redis 재고가 롤백되었는지 확인
        Map<String, Object> inventory = redisReservationService.getResourceStatus("inventory:" + TEST_PRODUCT_ID);
        System.out.println("\n📦 Redis 재고 상태:");
        System.out.println("  - Available: " + inventory.get("available"));
        System.out.println("  - Reserved: " + inventory.get("reserved"));

        assertEquals(INITIAL_STOCK, inventory.get("available"),
                "❌ Redis 재고가 롤백되지 않았습니다!");
        assertEquals(0, inventory.get("reserved"),
                "❌ Reserved 재고가 0이 아닙니다!");

        System.out.println("✅ Redis 재고 롤백 확인");
        System.out.println("\n✅✅✅ 결제 실패 시 보상 트랜잭션이 정상 작동했습니다!");
    }

    @Test
    @DisplayName("[Phase 2 실패 2] 주문 업데이트(markOrderAsPaid) 실패 시 보상 트랜잭션 동작")
    void test_orderUpdateFailure_triggersCompensation() {
        System.out.println("\n[테스트 2] 주문 업데이트 실패 시나리오");

        // [Given] 주문 업데이트를 실패하도록 Mock 설정
        doReturn(false).when(orderService).markOrderAsPaid(
                anyString(), anyString(), anyString()
        );

        System.out.println("⚠️ [Mock] markOrderAsPaid가 false를 반환하도록 설정");

        // [When] 예약 시도
        String customerId = "PHASE2-CUSTOMER-002";
        CompleteReservationRequest request = createReservationRequest(TEST_PRODUCT_ID, customerId);
        String correlationId = request.getCorrelationId();
        String url = "http://localhost:" + port + "/api/reservations/complete";

        ResponseEntity<CompleteReservationResponse> response =
                restTemplate.postForEntity(url, request, CompleteReservationResponse.class);

        // [Then] API 응답 검증
        System.out.println("\n📊 API 응답:");
        System.out.println("  - Status Code: " + response.getStatusCode());
        System.out.println("  - Body Status: " + (response.getBody() != null ? response.getBody().getStatus() : "null"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("FAILED", response.getBody().getStatus());

        System.out.println("✅ API 실패 응답 확인");

        // [검증 1] Redis 재고가 롤백되었는지 확인
        Map<String, Object> inventory = redisReservationService.getResourceStatus("inventory:" + TEST_PRODUCT_ID);
        assertEquals(INITIAL_STOCK, inventory.get("available"),
                "❌ 주문 업데이트 실패 시 Redis 재고가 롤백되지 않았습니다!");

        System.out.println("✅ Redis 재고 롤백 확인");

        // [검증 2] PG 환불이 호출되었는지 확인
        verify(mockPaymentGateway, atLeastOnce()).refundPayment(any());
        System.out.println("✅ PG 환불 호출 확인");

        System.out.println("\n✅✅✅ Phase 2 주문 업데이트 실패 시 보상 트랜잭션이 정상 작동했습니다!");
    }

    @Test
    @DisplayName("[Phase 2 실패 3] 재고 확정 실패 후 DB의 Reservation 상태 확인")
    void test_reservationStatusAfterPhase2Failure() {
        System.out.println("\n[테스트 3] Phase 2 실패 후 Reservation 상태 검증");

        // [Given] 재고 확정 실패 설정
        doAnswer(invocation -> {
            String reservationId = invocation.getArgument(1);
            String orderId = invocation.getArgument(2);
            String paymentId = invocation.getArgument(3);
            return com.example.payment.domain.model.inventory.InventoryConfirmation.failure(
                    reservationId, orderId, paymentId, "의도된 실패"
            );
        }).when(inventoryManagementService).confirmReservation(
                anyString(), anyString(), anyString(), anyString()
        );

        // [When] 예약 시도
        String customerId = "PHASE2-CUSTOMER-003";
        CompleteReservationRequest request = createReservationRequest(TEST_PRODUCT_ID, customerId);
        String url = "http://localhost:" + port + "/api/reservations/complete";

        ResponseEntity<CompleteReservationResponse> response =
                restTemplate.postForEntity(url, request, CompleteReservationResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());

        System.out.println("✅ API 실패 응답 확인");

        // [Then] DB에서 Reservation 조회
        // Phase 2 실패 시 Reservation이 생성되었을 수 있음 (Phase 1은 성공했으므로)
        // 이 경우 상태가 CANCELLED로 변경되어야 함

        List<Reservation> allReservations = reservationRepository.findAll();
        System.out.println("\n📊 DB의 Reservation 개수: " + allReservations.size());

        if (!allReservations.isEmpty()) {
            for (Reservation reservation : allReservations) {
                if (TEST_PRODUCT_ID.equals(reservation.getProductId())) {
                    System.out.println("  - Reservation ID: " + reservation.getId());
                    System.out.println("  - Status: " + reservation.getStatus());

                    // [검증] 상태가 CANCELLED인지 확인
                    assertEquals(Reservation.ReservationStatus.CANCELLED, reservation.getStatus(),
                            "❌ Phase 2 실패 후 Reservation 상태가 CANCELLED가 아닙니다!");
                }
            }
        }

        System.out.println("\n✅✅✅ Phase 2 실패 후 Reservation 상태가 정상적으로 처리되었습니다!");
    }

    @Test
    @DisplayName("[Phase 2 실패 4] 재고 부족 시나리오에서 재고가 정확하게 유지되는지 확인")
    void test_insufficientStock_inventoryConsistency() {
        System.out.println("\n[테스트 4] 재고 부족 시 일관성 검증");

        // [Given] 재고를 3개로 설정
        int limitedStock = 3;
        redisReservationService.initializeResource(
                "inventory:" + TEST_PRODUCT_ID,
                limitedStock,
                limitedStock
        );

        // [When] 5번 연속 예약 시도 (재고는 3개만 있음)
        int attemptCount = 5;
        int successCount = 0;
        int failureCount = 0;

        for (int i = 1; i <= attemptCount; i++) {
            String customerId = "PHASE2-CUSTOMER-MULTI-" + i;
            CompleteReservationRequest request = createReservationRequest(TEST_PRODUCT_ID, customerId);
            String url = "http://localhost:" + port + "/api/reservations/complete";

            ResponseEntity<CompleteReservationResponse> response =
                    restTemplate.postForEntity(url, request, CompleteReservationResponse.class);

            System.out.println("\n시도 #" + i + ":");
            System.out.println("  - Status: " + response.getStatusCode());

            if (response.getStatusCode() == HttpStatus.OK) {
                successCount++;
                System.out.println("  - 결과: 성공");
            } else {
                failureCount++;
                System.out.println("  - 결과: 실패 (" + response.getBody().getMessage() + ")");
            }

            // 각 시도 후 재고 확인
            Map<String, Object> inventory = redisReservationService.getResourceStatus("inventory:" + TEST_PRODUCT_ID);
            System.out.println("  - Available: " + inventory.get("available"));
        }

        // [Then] 최종 재고 확인
        Map<String, Object> finalInventory = redisReservationService.getResourceStatus("inventory:" + TEST_PRODUCT_ID);
        System.out.println("\n📦 최종 Redis 재고:");
        System.out.println("  - Available: " + finalInventory.get("available"));
        System.out.println("  - Reserved: " + finalInventory.get("reserved"));
        System.out.println("\n📊 결과 요약:");
        System.out.println("  - 성공: " + successCount + "회");
        System.out.println("  - 실패: " + failureCount + "회");

        // [검증] 성공한 예약 수가 재고 수와 일치하는지 확인
        assertEquals(limitedStock, successCount,
                "❌ 성공한 예약 수가 재고 수와 일치하지 않습니다!");
        assertEquals(attemptCount - limitedStock, failureCount,
                "❌ 실패한 예약 수가 예상과 다릅니다!");

        // Available이 0이 되었는지 확인
        assertEquals(0, finalInventory.get("available"),
                "❌ 최종 재고가 0이 아닙니다!");
        assertEquals(0, finalInventory.get("reserved"),
                "❌ Reserved 재고가 0이 아닙니다!");

        System.out.println("\n✅✅✅ 여러 번의 시도에서 재고 일관성이 완벽히 유지되었습니다!");
    }


    // ====================================
    // Helper Methods
    // ====================================

    private CompleteReservationRequest createReservationRequest(String productId, String customerId) {
        return CompleteReservationRequest.builder()
                .productId(productId)
                .customerId(customerId)
                .quantity(1)
                .clientId("phase2-test-client")
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
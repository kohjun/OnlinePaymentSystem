package com.example.payment;

import com.example.payment.application.dto.PaymentGatewayResult;
import com.example.payment.infrastructure.gateway.MockPaymentGateway;
import com.example.payment.infrastructure.util.ResourceReservationService; // 1. 임포트 추가
import com.example.payment.presentation.dto.request.CompleteReservationRequest;
import com.example.payment.presentation.dto.response.CompleteReservationResponse;

import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;

/**
 * [수정]
 * 1. @BeforeEach에서 Redis 재고를 초기화 (test-data.sql과 동기화)
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.wal.enabled=false"
        }
)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional
class SimpleIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    // [수정] 2. Redis 초기화를 위해 ResourceReservationService 주입
    @Autowired
    private ResourceReservationService redisReservationService;

    @MockBean
    private MockPaymentGateway mockPaymentGateway;

    @MockBean
    private org.springframework.kafka.core.KafkaTemplate<String, Object> kafkaTemplate;

    @MockBean
    private org.springframework.kafka.config.KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @MockBean
    private com.example.payment.infrastructure.persistence.jpa.WalLogJpaRepository walLogJpaRepository;


    @BeforeAll
    static void init() {
        System.out.println("\n=== SimpleIntegrationTest 시작 (Kafka/WAL Mock) ===\n");
    }

    @BeforeEach
    void setupMocksAndRedis() {
        // 1. MockGateway가 항상 성공하도록 설정
        Mockito.when(mockPaymentGateway.processPayment(any()))
                .thenReturn(PaymentGatewayResult.builder()
                        .success(true)
                        .transactionId("MOCK_TX_SUCCESS")
                        .approvalNumber("MOCK_APPROVAL_123")
                        .processedAmount(new BigDecimal("799.99"))
                        .build());

        // [수정] 2. Redis 재고 초기화 (test-data.sql과 동일하게)
        // test-data.sql은 H2 DB만 초기화하고, 이 코드는 Redis를 초기화합니다.
        redisReservationService.initializeResource("inventory:PROD-001", 100, 100);
        redisReservationService.initializeResource("inventory:PROD-002", 50, 50);
    }

    @Test
    @Order(1)
    @DisplayName("1. 헬스체크 테스트")
    void testHealthCheck() {
        // ... (이전과 동일, 통과할 것임) ...
        System.out.println("\n[테스트 1] 헬스체크");
        String url = "http://localhost:" + port + "/api/system/health";

        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        System.out.println("✅ 응답: " + response.getStatusCode());
        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertTrue(response.getBody().contains("HEALTHY"));
    }

    @Test
    @Order(2)
    @DisplayName("2. 단일 예약 테스트 (PROD-001 구매)")
    void testSingleReservation() {
        System.out.println("\n[테스트 2] 단일 예약 (PROD-001)");

        // [수정] 3. Redis 재고가 (100)으로 초기화되었으므로, 이 테스트는 이제 성공해야 함
        CompleteReservationRequest request = CompleteReservationRequest.builder()
                .productId("PROD-001")
                .customerId("TEST-001")
                .quantity(1)
                .clientId("test-client")
                .paymentInfo(CompleteReservationRequest.PaymentInfo.builder()
                        .amount(new BigDecimal("799.99"))
                        .currency("KRW")
                        .paymentMethod("CREDIT_CARD")
                        .build())
                .idempotencyKey(UUID.randomUUID().toString())
                .correlationId(UUID.randomUUID().toString())
                .build();

        String url = "http://localhost:" + port + "/api/reservations/complete";

        ResponseEntity<CompleteReservationResponse> response =
                restTemplate.postForEntity(url, request, CompleteReservationResponse.class);

        System.out.println("응답: " + response.getStatusCode());
        assertNotNull(response.getBody());
        System.out.println("상태: " + response.getBody().getStatus());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("SUCCESS", response.getBody().getStatus());
        assertNotNull(response.getBody().getReservation().getReservationId());
        System.out.println("✅ 예약 성공: " + response.getBody().getReservation().getReservationId());
    }

    @Test
    @Order(3)
    @DisplayName("3. 연속 예약 테스트 (3명, PROD-002)")
    void testSequentialReservations() {
        System.out.println("\n[테스트 3] 연속 예약 3명 (PROD-002, 재고 50개)");

        // [수정] 4. Redis 재고가 (50)으로 초기화되었으므로, 이 테스트는 이제 성공해야 함
        int successCount = 0;
        int failureCount = 0;

        for (int i = 1; i <= 3; i++) {
            System.out.println("\n--- 사용자 " + i + " ---");

            Mockito.when(mockPaymentGateway.processPayment(any()))
                    .thenReturn(PaymentGatewayResult.builder()
                            .success(true)
                            .transactionId("MOCK_TX_SUCCESS_" + i)
                            .approvalNumber("MOCK_APPROVAL_456_" + i)
                            .processedAmount(new BigDecimal("129.99"))
                            .build());

            CompleteReservationRequest request = CompleteReservationRequest.builder()
                    .productId("PROD-002")
                    .customerId("TEST-SEQ-" + String.format("%03d", i))
                    .quantity(1)
                    .clientId("test-client")
                    .paymentInfo(CompleteReservationRequest.PaymentInfo.builder()
                            .amount(new BigDecimal("129.99"))
                            .currency("KRW")
                            .paymentMethod("CREDIT_CARD")
                            .build())
                    .idempotencyKey(UUID.randomUUID().toString())
                    .correlationId(UUID.randomUUID().toString())
                    .build();

            String url = "http://localhost:" + port + "/api/reservations/complete";

            ResponseEntity<CompleteReservationResponse> response =
                    restTemplate.postForEntity(url, request, CompleteReservationResponse.class);

            System.out.println("응답: " + response.getStatusCode());

            if (response.getBody() != null && "SUCCESS".equals(response.getBody().getStatus())) {
                successCount++;
                System.out.println("✅ 성공");
            } else {
                failureCount++;
                System.out.println("❌ 실패: " +
                        (response.getBody() != null ? response.getBody().getMessage() : "알 수 없음"));
            }
        }

        System.out.println("\n=== 결과 ===");
        System.out.println("✅ 성공: " + successCount);
        System.out.println("❌ 실패: " + failureCount);

        assertEquals(3, successCount);
        assertEquals(0, failureCount);
    }
}
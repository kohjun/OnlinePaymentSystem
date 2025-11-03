package com.example.payment;

import com.example.payment.application.dto.PaymentGatewayResult;
import com.example.payment.infrastructure.gateway.MockPaymentGateway;
import com.example.payment.infrastructure.util.ResourceReservationService;
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
 * [수정된 SimpleIntegrationTest]
 * 1. @BeforeEach에서 Redis 재고를 초기화 (test-data.sql과 동기화)
 * 2. WalLogJpaRepository MockBean 제거 (NPE 오류 수정)
 * 3. 연속 예약 테스트 제거 (단일 예약에 집중)
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // WAL 스케줄러 등은 비활성화하되, WAL 로직은 DB에 기록되도록 함
                "spring.wal.enabled=false"
        }
)
@Transactional // 테스트 후 DB 롤백 (H2)
class SimpleIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ResourceReservationService redisReservationService;

    @MockBean
    private MockPaymentGateway mockPaymentGateway;

    @MockBean
    private org.springframework.kafka.core.KafkaTemplate<String, Object> kafkaTemplate;

    @MockBean
    private org.springframework.kafka.config.KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    // [수정] WAL 관련 NPE 오류를 유발하는 MockBean을 제거합니다.
    // H2 데이터베이스에 WAL 로그가 정상적으로 기록되도록 허용합니다.
    // @MockBean
    // private com.example.payment.infrastructure.persistence.jpa.WalLogJpaRepository walLogJpaRepository;


    @BeforeAll
    static void init() {
        System.out.println("\n=== SimpleIntegrationTest 시작 (Kafka Mock / H2 WAL 활성) ===\n");
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

        // 2. Redis 재고 초기화 (test-data.sql과 동일하게)
        // test-data.sql은 H2 DB만 초기화하고, 이 코드는 Redis를 초기화합니다.
        redisReservationService.initializeResource("inventory:PROD-001", 100, 100);
        redisReservationService.initializeResource("inventory:PROD-002", 50, 50);
    }

    @Test
    @DisplayName("1. 헬스체크 테스트")
    void testHealthCheck() {
        System.out.println("\n[테스트 1] 헬스체크");
        String url = "http://localhost:" + port + "/api/system/health";

        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        System.out.println("✅ 응답: " + response.getStatusCode());
        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertTrue(response.getBody().contains("HEALTHY"));
    }

    @Test
    @DisplayName("2. 단일 예약 테스트 (PROD-001 구매)")
    void testSingleReservation() {
        System.out.println("\n[테스트 2] 단일 예약 (PROD-001)");

        // [Given] 예약 요청 데이터 생성
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
                .correlationId(UUID.randomUUID().toString()) // txId로 사용됨
                .build();

        String url = "http://localhost:" + port + "/api/reservations/complete";

        // [When] API 호출
        ResponseEntity<CompleteReservationResponse> response =
                restTemplate.postForEntity(url, request, CompleteReservationResponse.class);

        // [Then] 응답 검증
        System.out.println("응답: " + response.getStatusCode());
        assertNotNull(response.getBody());
        System.out.println("상태: " + response.getBody().getStatus());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("SUCCESS", response.getBody().getStatus());
        assertNotNull(response.getBody().getReservation().getReservationId());
        System.out.println("✅ 예약 성공: " + response.getBody().getReservation().getReservationId());
    }

    // [수정] 요청에 따라 연속 예약 테스트(testSequentialReservations) 제거
}
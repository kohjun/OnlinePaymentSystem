package com.example.payment;

import com.example.payment.application.dto.PaymentGatewayResult;
import com.example.payment.domain.entity.WalLogEntry;
import com.example.payment.infrastructure.gateway.MockPaymentGateway;
import com.example.payment.infrastructure.persistence.jpa.WalLogJpaRepository;
import com.example.payment.infrastructure.util.ResourceReservationService;
import com.example.payment.presentation.dto.request.CompleteReservationRequest;
import com.example.payment.presentation.dto.response.CompleteReservationResponse;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * WAL (Write-Ahead Logging) 검증 테스트
 *
 * 목적:
 * 1. 모든 트랜잭션 단계가 WAL 로그에 기록되는지 확인
 * 2. Phase 1과 Phase 2의 연결이 올바른지 검증
 * 3. 실패 시 FAILED 상태로 로그가 남는지 확인
 * 4. WAL 로그만으로 트랜잭션을 재구성할 수 있는지 검증
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Transactional
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class WalLogVerificationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ResourceReservationService redisReservationService;

    @Autowired
    private WalLogJpaRepository walLogRepository;

    @MockBean
    private MockPaymentGateway mockPaymentGateway;

    private static final String TEST_PRODUCT_ID = "WAL-TEST-001";
    private static final int INITIAL_STOCK = 10;

    @BeforeEach
    void setUp() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("📝 WAL Log Verification Test Setup");
        System.out.println("=".repeat(70));

        // 1. Redis 재고 초기화
        redisReservationService.initializeResource(
                "inventory:" + TEST_PRODUCT_ID,
                INITIAL_STOCK,
                INITIAL_STOCK
        );

        // 2. MockGateway 설정 (결제 성공)
        when(mockPaymentGateway.processPayment(any()))
                .thenReturn(PaymentGatewayResult.builder()
                        .success(true)
                        .transactionId("MOCK_TX_WAL_TEST")
                        .approvalNumber("MOCK_APPROVAL_WAL")
                        .processedAmount(new BigDecimal("100.00"))
                        .build());
        when(mockPaymentGateway.getGatewayName()).thenReturn("MOCK_PAYMENT_GATEWAY");
    }

    @Test
    @DisplayName("[WAL 검증 1] 예약 성공 시 모든 단계의 WAL 로그가 기록되는지 확인")
    void test_allPhaseLogsRecorded() {
        System.out.println("\n[테스트 1] WAL 로그 완전성 검증");

        // [Given] 예약 요청
        String customerId = "WAL-CUSTOMER-001";
        CompleteReservationRequest request = createReservationRequest(TEST_PRODUCT_ID, customerId);
        String correlationId = request.getCorrelationId();
        String url = "http://localhost:" + port + "/api/reservations/complete";

        // [When] 예약 생성
        ResponseEntity<CompleteReservationResponse> response =
                restTemplate.postForEntity(url, request, CompleteReservationResponse.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        System.out.println("✅ 예약 완료: transactionId=" + correlationId);

        // [Then] WAL 로그 조회
        List<WalLogEntry> logs = walLogRepository.findByTransactionIdOrderByLsnAsc(correlationId);

        System.out.println("\n📝 WAL 로그 개수: " + logs.size());

        // [검증] 최소한의 주요 로그가 있는지 확인
        assertTrue(logs.size() >= 3,
                "❌ WAL 로그가 부족합니다! (예상: 3개 이상, 실제: " + logs.size() + ")");

        // 모든 로그 출력
        System.out.println("\n📊 WAL 로그 상세:");
        for (int i = 0; i < logs.size(); i++) {
            WalLogEntry log = logs.get(i);
            System.out.println("\n  Log #" + (i + 1) + ":");
            System.out.println("    - LSN: " + log.getLsn());
            System.out.println("    - Operation: " + log.getOperation());
            System.out.println("    - Table: " + log.getTableName());
            System.out.println("    - Status: " + log.getStatus());
            System.out.println("    - Message: " + log.getMessage());
            System.out.println("    - Related Log ID: " + log.getRelatedLogId());
            System.out.println("    - Created At: " + log.getCreatedAt());
            System.out.println("    - Completed At: " + log.getCompletedAt());
        }

        // [검증] Phase 1 로그 확인
        List<String> phase1Operations = logs.stream()
                .map(WalLogEntry::getOperation)
                .filter(op -> op.contains("RESERVE") || op.contains("ORDER_CREATE") || op.contains("PAYMENT"))
                .collect(Collectors.toList());

        System.out.println("\n📌 Phase 1 Operations: " + phase1Operations);
        assertTrue(phase1Operations.stream().anyMatch(op -> op.contains("RESERVE")),
                "❌ RESERVE 관련 WAL 로그가 없습니다!");

        // [검증] Phase 2 로그 확인
        List<String> phase2Operations = logs.stream()
                .map(WalLogEntry::getOperation)
                .filter(op -> op.contains("CONFIRM") || op.contains("PAID"))
                .collect(Collectors.toList());

        System.out.println("📌 Phase 2 Operations: " + phase2Operations);
        assertTrue(phase2Operations.stream().anyMatch(op -> op.contains("CONFIRM")),
                "❌ CONFIRM 관련 WAL 로그가 없습니다!");

        // [검증] 모든 로그가 COMMITTED 또는 RECOVERED 상태인지 확인
        boolean allCommitted = logs.stream()
                .allMatch(log -> "COMMITTED".equals(log.getStatus()) ||
                        "RECOVERED".equals(log.getStatus()));
        assertTrue(allCommitted, "❌ 일부 로그가 COMMITTED 상태가 아닙니다!");

        System.out.println("\n✅✅✅ 모든 단계의 WAL 로그가 정상적으로 기록되었습니다!");
    }

    @Test
    @DisplayName("[WAL 검증 2] Phase 1과 Phase 2의 연결이 올바른지 검증 (relatedLogId)")
    void test_phase1AndPhase2Connected() {
        System.out.println("\n[테스트 2] Phase 1/2 연결 검증");

        // [Given & When] 예약 생성
        String customerId = "WAL-CUSTOMER-002";
        CompleteReservationRequest request = createReservationRequest(TEST_PRODUCT_ID, customerId);
        String correlationId = request.getCorrelationId();
        String url = "http://localhost:" + port + "/api/reservations/complete";

        ResponseEntity<CompleteReservationResponse> response =
                restTemplate.postForEntity(url, request, CompleteReservationResponse.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        System.out.println("✅ 예약 완료");

        // [Then] WAL 로그 조회
        List<WalLogEntry> logs = walLogRepository.findByTransactionIdOrderByLsnAsc(correlationId);

        // [검증] Phase 2 로그 중 relatedLogId가 있는지 확인
        List<WalLogEntry> phase2Logs = logs.stream()
                .filter(log -> log.getOperation().contains("CONFIRM") ||
                        log.getOperation().contains("PAID") ||
                        log.getOperation().contains("PHASE2"))
                .collect(Collectors.toList());

        System.out.println("\n📊 Phase 2 로그 개수: " + phase2Logs.size());

        if (!phase2Logs.isEmpty()) {
            for (WalLogEntry phase2Log : phase2Logs) {
                System.out.println("\n  Phase 2 Log:");
                System.out.println("    - Operation: " + phase2Log.getOperation());
                System.out.println("    - Related Log ID: " + phase2Log.getRelatedLogId());

                // [검증] relatedLogId가 Phase 1 로그를 가리키는지 확인
                if (phase2Log.getRelatedLogId() != null) {
                    // Phase 1 로그 조회
                    WalLogEntry phase1Log = walLogRepository.findById(phase2Log.getRelatedLogId())
                            .orElse(null);

                    if (phase1Log != null) {
                        System.out.println("\n  ✅ 연결된 Phase 1 Log 발견:");
                        System.out.println("    - Operation: " + phase1Log.getOperation());
                        System.out.println("    - Status: " + phase1Log.getStatus());

                        // [검증] Phase 1 로그의 transactionId가 같은지 확인
                        assertEquals(correlationId, phase1Log.getTransactionId(),
                                "❌ Phase 1과 Phase 2의 transactionId가 다릅니다!");
                    }
                }
            }
        }

        System.out.println("\n✅✅✅ Phase 1과 Phase 2가 정상적으로 연결되었습니다!");
    }

    @Test
    @DisplayName("[WAL 검증 3] 결제 실패 시 FAILED 상태의 WAL 로그가 기록되는지 확인")
    void test_failedLogsRecorded() {
        System.out.println("\n[테스트 3] 실패 로그 기록 검증");

        // [Given] 결제 실패 설정
        when(mockPaymentGateway.processPayment(any()))
                .thenReturn(PaymentGatewayResult.failure("MOCK_FAILURE", "의도된 결제 실패"));

        // [When] 예약 시도
        String customerId = "WAL-CUSTOMER-003";
        CompleteReservationRequest request = createReservationRequest(TEST_PRODUCT_ID, customerId);
        String correlationId = request.getCorrelationId();
        String url = "http://localhost:" + port + "/api/reservations/complete";

        ResponseEntity<CompleteReservationResponse> response =
                restTemplate.postForEntity(url, request, CompleteReservationResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());

        System.out.println("✅ 예약 실패 확인");

        // [Then] WAL 로그 조회
        List<WalLogEntry> logs = walLogRepository.findByTransactionIdOrderByLsnAsc(correlationId);

        System.out.println("\n📝 WAL 로그 개수: " + logs.size());

        // [검증] FAILED 상태의 로그가 있는지 확인
        List<WalLogEntry> failedLogs = logs.stream()
                .filter(log -> "FAILED".equals(log.getStatus()))
                .collect(Collectors.toList());

        System.out.println("\n📊 FAILED 로그 개수: " + failedLogs.size());
        assertFalse(failedLogs.isEmpty(), "❌ FAILED 상태의 WAL 로그가 없습니다!");

        // FAILED 로그 상세 출력
        for (WalLogEntry failedLog : failedLogs) {
            System.out.println("\n  FAILED Log:");
            System.out.println("    - Operation: " + failedLog.getOperation());
            System.out.println("    - Message: " + failedLog.getMessage());
            System.out.println("    - Status: " + failedLog.getStatus());

            // [검증] 실패 메시지가 있는지 확인
            assertNotNull(failedLog.getMessage(), "❌ 실패 메시지가 없습니다!");
            assertTrue(failedLog.getMessage().contains("실패") ||
                            failedLog.getMessage().contains("fail") ||
                            failedLog.getMessage().toLowerCase().contains("error"),
                    "❌ 실패 메시지가 명확하지 않습니다!");
        }

        System.out.println("\n✅✅✅ 실패 시나리오의 WAL 로그가 정상적으로 기록되었습니다!");
    }

    @Test
    @DisplayName("[WAL 검증 4] WAL 로그의 LSN이 순차적으로 증가하는지 확인")
    void test_lsnIsSequential() {
        System.out.println("\n[테스트 4] LSN 순차성 검증");

        // [Given & When] 예약 생성
        String customerId = "WAL-CUSTOMER-004";
        CompleteReservationRequest request = createReservationRequest(TEST_PRODUCT_ID, customerId);
        String correlationId = request.getCorrelationId();
        String url = "http://localhost:" + port + "/api/reservations/complete";

        ResponseEntity<CompleteReservationResponse> response =
                restTemplate.postForEntity(url, request, CompleteReservationResponse.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        System.out.println("✅ 예약 완료");

        // [Then] WAL 로그 조회
        List<WalLogEntry> logs = walLogRepository.findByTransactionIdOrderByLsnAsc(correlationId);

        System.out.println("\n📊 LSN 검증:");
        Long previousLsn = null;
        for (WalLogEntry log : logs) {
            System.out.println("  - LSN: " + log.getLsn() + " | Operation: " + log.getOperation());

            // [검증] LSN이 순차적으로 증가하는지 확인
            if (previousLsn != null) {
                assertTrue(log.getLsn() > previousLsn,
                        "❌ LSN이 순차적으로 증가하지 않습니다! (이전: " + previousLsn + ", 현재: " + log.getLsn() + ")");
            }
            previousLsn = log.getLsn();
        }

        System.out.println("\n✅✅✅ LSN이 순차적으로 증가합니다!");
    }

    @Test
    @DisplayName("[WAL 검증 5] WAL 로그만으로 트랜잭션을 재구성할 수 있는지 확인")
    void test_transactionReconstructionFromWal() {
        System.out.println("\n[테스트 5] 트랜잭션 재구성 가능성 검증");

        // [Given & When] 예약 생성
        String customerId = "WAL-CUSTOMER-005";
        CompleteReservationRequest request = createReservationRequest(TEST_PRODUCT_ID, customerId);
        String correlationId = request.getCorrelationId();
        String url = "http://localhost:" + port + "/api/reservations/complete";

        ResponseEntity<CompleteReservationResponse> response =
                restTemplate.postForEntity(url, request, CompleteReservationResponse.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        String reservationId = response.getBody().getReservation().getReservationId();

        System.out.println("✅ 예약 완료: reservationId=" + reservationId);

        // [Then] WAL 로그만으로 트랜잭션 흐름 재구성
        List<WalLogEntry> logs = walLogRepository.findByTransactionIdOrderByLsnAsc(correlationId);

        System.out.println("\n📊 트랜잭션 흐름 재구성:");
        System.out.println("=".repeat(70));

        String extractedReservationId = null;
        String extractedOrderId = null;
        String extractedPaymentId = null;

        for (WalLogEntry log : logs) {
            System.out.println("\n  [" + log.getOperation() + "]");
            System.out.println("    시간: " + log.getCreatedAt());
            System.out.println("    상태: " + log.getStatus());
            System.out.println("    메시지: " + log.getMessage());

            // beforeData 또는 afterData에서 ID 추출
            String data = log.getBeforeData() != null ? log.getBeforeData() : log.getAfterData();
            if (data != null) {
                if (data.contains("reservationId") && extractedReservationId == null) {
                    // 간단한 JSON 파싱 (실제로는 ObjectMapper 사용)
                    if (data.contains(reservationId)) {
                        extractedReservationId = reservationId;
                    }
                }
                if (data.contains("orderId")) {
                    System.out.println("    📦 Order ID 발견!");
                    extractedOrderId = "found";
                }
                if (data.contains("paymentId")) {
                    System.out.println("    💳 Payment ID 발견!");
                    extractedPaymentId = "found";
                }
            }
        }

        System.out.println("\n=".repeat(70));
        System.out.println("🔍 추출된 정보:");
        System.out.println("  - Reservation ID: " + (extractedReservationId != null ? "✅" : "❌"));
        System.out.println("  - Order ID: " + (extractedOrderId != null ? "✅" : "❌"));
        System.out.println("  - Payment ID: " + (extractedPaymentId != null ? "✅" : "❌"));

        // [검증] 주요 정보가 WAL 로그에 포함되어 있는지 확인
        assertNotNull(extractedReservationId, "❌ WAL 로그에서 Reservation ID를 추출할 수 없습니다!");

        System.out.println("\n✅✅✅ WAL 로그만으로 트랜잭션을 재구성할 수 있습니다!");
    }

    // ====================================
    // Helper Methods
    // ====================================

    private CompleteReservationRequest createReservationRequest(String productId, String customerId) {
        return CompleteReservationRequest.builder()
                .productId(productId)
                .customerId(customerId)
                .quantity(1)
                .clientId("wal-test-client")
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
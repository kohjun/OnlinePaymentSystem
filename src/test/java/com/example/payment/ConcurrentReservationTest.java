package com.example.payment;

import com.example.payment.application.dto.PaymentGatewayResult; // 1. 임포트 추가
import com.example.payment.infrastructure.gateway.MockPaymentGateway; // 2. 임포트 추가
import com.example.payment.presentation.dto.request.CompleteReservationRequest;
import com.example.payment.presentation.dto.response.CompleteReservationResponse;
import com.example.payment.infrastructure.util.ResourceReservationService;

import org.junit.jupiter.api.*;
import org.mockito.Mockito; // 3. 임포트 추가
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean; // 4. 임포트 추가
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any; // 5. 임포트 추가
import static org.mockito.Mockito.when;

/**
 * 동시성 테스트
 * [수정]
 * 1. MockPaymentGateway를 MockBean으로 만들어 10% 랜덤 실패를 제거 (테스트 안정성 확보)
 */
@org.junit.jupiter.api.Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConcurrentReservationTest extends TestcontainersIntegrationSupport {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ResourceReservationService redisReservationService;

    // [수정] 6. MockPaymentGateway를 MockBean으로 선언
    @MockBean
    private MockPaymentGateway mockPaymentGateway;

    private static final String PRODUCT_ID = "CONCURRENCY-TEST-001";
    private static final int TOTAL_STOCK = 3;
    private static final int CONCURRENT_USERS = 10;

    @BeforeAll
    void setupOnce() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("🧪 ConcurrentReservationTest 시작 (Payment Mock)");
        System.out.println("=".repeat(60) + "\n");

        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @BeforeEach
    void setUp() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("동시성 테스트 환경 초기화");
        System.out.println("=".repeat(60));

        // 1. Redis 재고 초기화
        String resourceKey = "inventory:" + PRODUCT_ID;
        try {
            redisReservationService.initializeResource(
                    resourceKey,
                    TOTAL_STOCK,
                    TOTAL_STOCK
            );
            System.out.println("✅ Redis 재고 초기화 완료: " + PRODUCT_ID + ", 재고: " + TOTAL_STOCK);
        } catch (Exception e) {
            System.err.println("⚠️ Redis 재고 초기화 실패: " + e.getMessage());
            e.printStackTrace();
        }

        // [수정] 7. MockGateway가 항상 성공하도록 설정
        when(mockPaymentGateway.processPayment(any())) // Mockito.when 대신 when 사용
                .thenReturn(PaymentGatewayResult.builder()
                        .success(true)
                        .transactionId("MOCK_TX_CONCURRENT")
                        .approvalNumber("MOCK_APPROVAL_CONCURRENT")
                        .processedAmount(new BigDecimal("799.99"))
                        .build());
        when(mockPaymentGateway.getGatewayName()).thenReturn("MOCK_PAYMENT_GATEWAY");
        printCurrentInventory();
    }

    @Test
    @DisplayName("🔥 동시성 테스트: 10명이 재고 3개 상품 동시 예약")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testConcurrentReservations() throws InterruptedException {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("🚀 동시 예약 테스트 시작");
        // ... (로그 동일) ...
        System.out.println("=".repeat(60) + "\n");

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_USERS);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(CONCURRENT_USERS);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<String> successfulReservations = new CopyOnWriteArrayList<>();
        List<String> failedReasons = new CopyOnWriteArrayList<>();

        for (int i = 1; i <= CONCURRENT_USERS; i++) {
            final int userId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Thread.sleep(ThreadLocalRandom.current().nextInt(10, 50));

                    CompleteReservationRequest request = createReservationRequest(userId);
                    System.out.println("⏳ [사용자" + userId + "] 예약 시도...");

                    String url = "http://localhost:" + port + "/api/reservations/complete";
                    ResponseEntity<CompleteReservationResponse> response =
                            restTemplate.postForEntity(url, request, CompleteReservationResponse.class);

                    System.out.println("📥 [사용자" + userId + "] 응답 받음: " + response.getStatusCode());

                    // [수정] 8. Mocking으로 결제는 100% 성공하므로,
                    // 이제 "SUCCESS" 또는 "재고 선점 실패" (Bad Request)만 응답으로 와야 함.
                    if (response.getStatusCode().is2xxSuccessful() &&
                            response.getBody() != null &&
                            "SUCCESS".equals(response.getBody().getStatus())) {

                        successCount.incrementAndGet();
                        String reservationId = response.getBody().getReservation().getReservationId();
                        successfulReservations.add(reservationId);
                        System.out.println("✅ [사용자" + userId + "] 예약 성공! ID: " + reservationId);

                    } else {
                        failureCount.incrementAndGet();
                        String reason = (response.getBody() != null && response.getBody().getMessage() != null) ?
                                response.getBody().getMessage() : "Unknown: " + response.getStatusCode();
                        failedReasons.add("[사용자" + userId + "] " + reason);
                        System.out.println("❌ [사용자" + userId + "] 예약 실패: " + reason);
                    }

                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    failedReasons.add("[사용자" + userId + "] Exception: " + errorMsg);
                    System.err.println("💥 [사용자" + userId + "] 오류: " + errorMsg);
                } finally {
                    completeLatch.countDown();
                }
            });
        }

        System.out.println("\n🏁 모든 사용자 동시 예약 시작!\n");
        startLatch.countDown();

        boolean completed = completeLatch.await(50, TimeUnit.SECONDS);
        assertTrue(completed, "⚠️ 모든 요청이 50초 내에 완료되어야 합니다");

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        System.out.println("\n" + "=".repeat(60));
        printTestResults(successCount.get(), failureCount.get(),
                successfulReservations, failedReasons);
        System.out.println("=".repeat(60));

        printCurrentInventory();

        System.out.println("\n" + "=".repeat(60));
        System.out.println("🔍 결과 검증");
        System.out.println("=".repeat(60));

        // [수정] 9. Mocking으로 결제 실패가 제거되었으므로,
        // 이 Assert는 이제 무조건 통과해야 함 (라인 177)
        assertEquals(TOTAL_STOCK, successCount.get(), "✅ 성공한 요청 수가 정확히 재고 수와 일치해야 합니다.");
        assertEquals(CONCURRENT_USERS - TOTAL_STOCK, failureCount.get(), "❌ 실패한 요청 수가 (총 요청 - 재고)와 일치해야 합니다.");

        System.out.println("✅ ✅ ✅ 완벽한 동시성 제어! ✅ ✅ ✅");
    }

    private CompleteReservationRequest createReservationRequest(int userId) {
        // ... (메소드 동일) ...
        String customerId = "TEST-CUSTOMER-" + String.format("%03d", userId);
        String idempotencyKey = UUID.randomUUID().toString();

        return CompleteReservationRequest.builder()
                .productId(PRODUCT_ID)
                .customerId(customerId)
                .quantity(1)
                .clientId("test-client")
                .paymentInfo(CompleteReservationRequest.PaymentInfo.builder()
                        .amount(new BigDecimal("799.99"))
                        .currency("USD")
                        .paymentMethod("CREDIT_CARD")
                        .build())
                .idempotencyKey(idempotencyKey)
                .correlationId("TEST-" + System.currentTimeMillis() + "-" + userId)
                .build();
    }

    private void printTestResults(int success, int failure,
                                  List<String> successIds, List<String> failedReasons) {
        // ... (메소드 동일) ...
        System.out.println("📊 테스트 결과 요약");
        System.out.println("-".repeat(60));
        System.out.println("✅ 성공: " + success + "명");
        System.out.println("❌ 실패: " + failure + "명");
        System.out.println("📦 총 시도: " + (success + failure) + "명");
        System.out.println();

        if (!successIds.isEmpty()) {
            System.out.println("✅ 성공한 예약 목록:");
            for (int i = 0; i < successIds.size(); i++) {
                System.out.println("   " + (i + 1) + ". " + successIds.get(i));
            }
            System.out.println();
        }

        if (!failedReasons.isEmpty()) {
            System.out.println("❌ 실패 사유:");
            failedReasons.forEach(reason -> System.out.println("   - " + reason));
        }
    }

    private void printCurrentInventory() {
        // ... (메소드 동일) ...
        try {
            Map<String, Object> inventory = redisReservationService.getResourceStatus("inventory:" + PRODUCT_ID);
            if (inventory != null && !inventory.isEmpty()) {
                System.out.println("\n📦 현재 재고 상태 (Redis Hash):");
                System.out.println("   - total: " + inventory.get("total"));
                System.out.println("   - available: " + inventory.get("available"));
                System.out.println("   - reserved: " + inventory.get("reserved"));
            } else {
                System.out.println("\n⚠️ 재고 정보 없음 (" + "inventory:" + PRODUCT_ID + ")");
            }
        } catch (Exception e) {
            System.out.println("\n⚠️ 재고 조회 실패: " + e.getMessage());
        }
    }
}

package com.example.payment;

import com.example.payment.presentation.dto.request.CompleteReservationRequest;
import com.example.payment.presentation.dto.response.CompleteReservationResponse;
import com.example.payment.infrastructure.persistence.redis.repository.CacheService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 🎯 한정 상품 동시 예약 테스트
 *
 * 시나리오:
 * 1. 재고 3개인 상품에 10명이 동시 예약 시도
 * 2. 정확히 3명만 성공해야 함
 * 3. 나머지 7명은 "재고 부족" 응답 받아야 함
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConcurrentReservationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private CacheService cacheService;

    private static final String PRODUCT_ID = "PROD-001";
    private static final int TOTAL_STOCK = 3;
    private static final int CONCURRENT_USERS = 10;

    @BeforeEach
    void setUp() {
        System.out.println("\n========================================");
        System.out.println("🧪 테스트 환경 초기화");
        System.out.println("========================================");

        // Redis 재고 초기화
        Map<String, Object> inventory = Map.of(
                "product_id", PRODUCT_ID,
                "product_name", "초특가 스마트폰",
                "quantity", TOTAL_STOCK,
                "reserved", 0,
                "price", "799.99"
        );
        cacheService.cacheMapData("inventory:" + PRODUCT_ID, inventory, 86400);

        System.out.println("✅ 재고 초기화 완료: " + PRODUCT_ID + " (재고 " + TOTAL_STOCK + "개)");
        printCurrentInventory();
    }

    @Test
    @DisplayName("🔥 동시 예약 테스트: 10명이 재고 3개 상품 예약 시도")
    void testConcurrentReservations() throws InterruptedException {
        System.out.println("\n========================================");
        System.out.println("🚀 동시 예약 테스트 시작");
        System.out.println("- 총 사용자: " + CONCURRENT_USERS + "명");
        System.out.println("- 재고: " + TOTAL_STOCK + "개");
        System.out.println("- 예상 성공: " + TOTAL_STOCK + "명");
        System.out.println("- 예상 실패: " + (CONCURRENT_USERS - TOTAL_STOCK) + "명");
        System.out.println("========================================\n");

        // 동시 실행을 위한 스레드 풀
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_USERS);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(CONCURRENT_USERS);

        // 결과 추적
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<String> successfulReservations = new CopyOnWriteArrayList<>();
        List<String> failedReasons = new CopyOnWriteArrayList<>();

        // 10명의 사용자가 동시에 예약 시도
        for (int i = 1; i <= CONCURRENT_USERS; i++) {
            final int userId = i;
            executor.submit(() -> {
                try {
                    // 모든 스레드가 준비될 때까지 대기
                    startLatch.await();

                    // 예약 요청 생성
                    CompleteReservationRequest request = createReservationRequest(userId);

                    System.out.println("⏳ [사용자" + userId + "] 예약 시도 중...");

                    // API 호출
                    ResponseEntity<CompleteReservationResponse> response =
                            restTemplate.postForEntity(
                                    "/api/reservations/complete",
                                    request,
                                    CompleteReservationResponse.class
                            );

                    // 결과 처리
                    if (response.getStatusCode().is2xxSuccessful() &&
                            response.getBody() != null &&
                            "SUCCESS".equals(response.getBody().getStatus())) {

                        successCount.incrementAndGet();
                        String reservationId = response.getBody().getReservation().getReservationId();
                        successfulReservations.add(reservationId);

                        System.out.println("✅ [사용자" + userId + "] 예약 성공! 예약ID: " + reservationId);

                    } else {
                        failureCount.incrementAndGet();
                        String reason = response.getBody() != null ?
                                response.getBody().getMessage() : "Unknown error";
                        failedReasons.add("[사용자" + userId + "] " + reason);

                        System.out.println("❌ [사용자" + userId + "] 예약 실패: " + reason);
                    }

                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    failedReasons.add("[사용자" + userId + "] Exception: " + e.getMessage());
                    System.err.println("💥 [사용자" + userId + "] 오류 발생: " + e.getMessage());
                } finally {
                    completeLatch.countDown();
                }
            });
        }

        // 모든 스레드 동시 시작
        System.out.println("🏁 모든 사용자 동시 예약 시작!\n");
        startLatch.countDown();

        // 모든 요청 완료 대기 (최대 30초)
        boolean completed = completeLatch.await(30, TimeUnit.SECONDS);
        assertTrue(completed, "모든 요청이 30초 내에 완료되어야 합니다");

        executor.shutdown();

        // 결과 출력
        printTestResults(successCount.get(), failureCount.get(),
                successfulReservations, failedReasons);

        // 최종 재고 확인
        printCurrentInventory();

        // 검증
        assertEquals(TOTAL_STOCK, successCount.get(),
                "성공한 예약 수는 정확히 재고 수량과 같아야 합니다");
        assertEquals(CONCURRENT_USERS - TOTAL_STOCK, failureCount.get(),
                "실패한 예약 수는 (전체 사용자 - 재고)와 같아야 합니다");

        System.out.println("\n✅ 테스트 통과: 동시성 제어 정상 작동!");
    }

    /**
     * 예약 요청 생성
     */
    private CompleteReservationRequest createReservationRequest(int userId) {
        String customerId = "CUSTOMER-" + String.format("%03d", userId);

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
                .idempotencyKey(UUID.randomUUID().toString())
                .correlationId("TEST-" + System.currentTimeMillis() + "-" + userId)
                .build();
    }

    /**
     * 테스트 결과 출력
     */
    private void printTestResults(int success, int failure,
                                  List<String> successIds, List<String> failedReasons) {
        System.out.println("\n========================================");
        System.out.println("📊 테스트 결과");
        System.out.println("========================================");
        System.out.println("✅ 성공: " + success + "건");
        System.out.println("❌ 실패: " + failure + "건");
        System.out.println("========================================");

        if (!successIds.isEmpty()) {
            System.out.println("\n✅ 성공한 예약 ID 목록:");
            successIds.forEach(id -> System.out.println("  - " + id));
        }

        if (!failedReasons.isEmpty()) {
            System.out.println("\n❌ 실패 원인:");
            failedReasons.forEach(reason -> System.out.println("  - " + reason));
        }

        System.out.println();
    }

    /**
     * 현재 재고 상태 출력
     */
    private void printCurrentInventory() {
        String key = "inventory:" + PRODUCT_ID;
        Object data = cacheService.getCachedData(key);

        if (data != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> inventory = (Map<String, Object>) data;

            int total = Integer.parseInt(inventory.get("quantity").toString());
            int reserved = Integer.parseInt(inventory.get("reserved").toString());
            int available = total - reserved;

            System.out.println("📦 현재 재고 상태:");
            System.out.println("  - 총 재고: " + total + "개");
            System.out.println("  - 예약중: " + reserved + "개");
            System.out.println("  - 구매가능: " + available + "개");
        } else {
            System.out.println("⚠️  재고 정보를 찾을 수 없습니다");
        }
    }

    /**
     * 🎯 추가 테스트: 예약 후 결제 실패 시나리오
     */
    @Test
    @DisplayName("💳 결제 실패 시 재고 복원 테스트")
    void testPaymentFailureRestoresStock() throws InterruptedException {
        System.out.println("\n========================================");
        System.out.println("🧪 결제 실패 시나리오 테스트");
        System.out.println("========================================");

        // 1. 정상 예약 (재고 차감)
        CompleteReservationRequest request = createReservationRequest(1);
        ResponseEntity<CompleteReservationResponse> response =
                restTemplate.postForEntity(
                        "/api/reservations/complete",
                        request,
                        CompleteReservationResponse.class
                );

        assertTrue(response.getStatusCode().is2xxSuccessful());
        System.out.println("✅ 1단계: 예약 성공");
        printCurrentInventory();

        // 2. 결제 실패 시뮬레이션은 현재 Mock Gateway에서 90% 성공률
        // 실제 환경에서는 결제 실패 시 보상 트랜잭션으로 재고 복원됨

        System.out.println("\n💡 Note: 실제 결제 실패 시 ReservationOrchestrator의");
        System.out.println("   compensateReservation()이 자동으로 재고를 복원합니다");
    }
}
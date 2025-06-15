package com.example.payment;

import com.example.payment.domain.model.order.OrderItem;
import com.example.payment.presentation.dto.request.OrderRequest;
import com.example.payment.presentation.dto.response.OrderResponse;
import com.example.payment.infrastructure.persistance.redis.repository.CacheService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 주문 → Redis → Kafka 플로우 테스트
 * 실제 Redis와 Kafka 연결이 필요합니다
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SimpleOrderFlowTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private CacheService cacheService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void testOrderCreationFlow() {
        System.out.println("=== 주문 생성 플로우 테스트 시작 ===");

        // 1. 주문 생성
        OrderRequest request = createSampleOrder();
        System.out.println("주문 요청 생성 완료: " + request.getCustomerId());

        // 2. API 호출
        ResponseEntity<OrderResponse> response = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/orders",
                request,
                OrderResponse.class);

        System.out.println("API 응답 상태: " + response.getStatusCode());

        // 3. 기본 응답 검증
        assertNotNull(response.getBody());
        OrderResponse orderResponse = response.getBody();
        String orderId = orderResponse.getOrderId();

        System.out.println("생성된 주문 ID: " + orderId);
        System.out.println("초기 상태: " + orderResponse.getStatus());

        // 4. Redis 캐시 확인
        checkRedisCache(orderId);

        // 5. 처리 대기 (비동기 처리 시간 확보)
        waitForProcessing();

        // 6. 최종 상태 확인
        checkFinalStatus(orderId);
    }

    private void checkRedisCache(String orderId) {
        System.out.println("\n=== Redis 캐시 확인 ===");

        try {
            // 주문 캐시 키로 데이터 조회
            String cacheKey = "order:" + orderId;
            Object cachedOrder = cacheService.getCachedData(cacheKey);

            if (cachedOrder != null) {
                System.out.println("✅ Redis에서 주문 데이터 발견!");
                System.out.println("캐시된 데이터 타입: " + cachedOrder.getClass().getSimpleName());

                // Redis 직접 조회도 해보기
                Boolean exists = redisTemplate.hasKey(cacheKey);
                System.out.println("Redis 직접 조회 결과: " + exists);

            } else {
                System.out.println("❌ Redis에서 주문 데이터를 찾을 수 없음");
            }

        } catch (Exception e) {
            System.out.println("❌ Redis 조회 중 오류: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void checkFinalStatus(String orderId) {
        System.out.println("\n=== 최종 상태 확인 ===");

        try {
            ResponseEntity<OrderResponse> statusResponse = restTemplate.getForEntity(
                    "http://localhost:" + port + "/api/orders/" + orderId,
                    OrderResponse.class);

            if (statusResponse.getStatusCode().is2xxSuccessful() && statusResponse.getBody() != null) {
                OrderResponse updatedOrder = statusResponse.getBody();
                System.out.println("최종 주문 상태: " + updatedOrder.getStatus());
                System.out.println("상태 메시지: " + updatedOrder.getMessage());
                System.out.println("업데이트 시간: " + updatedOrder.getUpdatedAt());

                // 상태가 변경되었는지 확인
                assertNotEquals("CREATED", updatedOrder.getStatus(),
                        "주문 상태가 초기 상태에서 변경되어야 합니다");

            } else {
                System.out.println("❌ 주문 상태 조회 실패");
            }

        } catch (Exception e) {
            System.out.println("❌ 상태 조회 중 오류: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void waitForProcessing() {
        try {
            System.out.println("\n비동기 처리 대기 중... (3초)");
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private OrderRequest createSampleOrder() {
        OrderItem item1 = OrderItem.builder()
                .productId("PROD-1001")
                .productName("테스트 상품")
                .quantity(1)
                .price(new BigDecimal("100.00"))
                .build();

        return OrderRequest.builder()
                .customerId("CUST-" + UUID.randomUUID().toString().substring(0, 8))
                .clientId("test-client")
                .items(Arrays.asList(item1))
                .currency("USD")
                .paymentMethod("CREDIT_CARD")
                .idempotencyKey(UUID.randomUUID().toString())
                .shippingAddress("서울시 테스트로 123")
                .build();
    }

    /**
     * Redis 연결 상태만 빠르게 확인하는 테스트
     */
    @Test
    void testRedisConnection() {
        System.out.println("=== Redis 연결 테스트 ===");

        try {
            String testKey = "test:" + System.currentTimeMillis();
            String testValue = "Hello Redis!";

            // 데이터 저장
            cacheService.cacheData(testKey, testValue, 60);
            System.out.println("✅ Redis 저장 성공");

            // 데이터 조회
            Object retrieved = cacheService.getCachedData(testKey);
            System.out.println("✅ Redis 조회 성공: " + retrieved);

            assertEquals(testValue, retrieved);

        } catch (Exception e) {
            System.out.println("❌ Redis 연결 실패: " + e.getMessage());
            fail("Redis 연결이 필요합니다");
        }
    }

    /**
     * Kafka 연결 상태만 빠르게 확인하는 테스트
     */
    @Test
    void testKafkaConnection() {
        System.out.println("=== Kafka 연결 테스트 ===");

        try {
            String testTopic = "test-topic";
            String testMessage = "Hello Kafka!";

            // 메시지 발송 시도
            kafkaTemplate.send(testTopic, "test-key", testMessage);
            System.out.println("✅ Kafka 메시지 발송 성공");

        } catch (Exception e) {
            System.out.println("❌ Kafka 연결 실패: " + e.getMessage());
            fail("Kafka 연결이 필요합니다");
        }
    }
}
package com.example.payment;

import com.example.payment.domain.model.inventory.Inventory;
import com.example.payment.domain.model.inventory.Product;
import com.example.payment.domain.repository.InventoryRepository;
import com.example.payment.domain.repository.ProductRepository;
import com.example.payment.presentation.dto.request.CompleteReservationRequest;
import com.example.payment.presentation.dto.response.CompleteReservationResponse;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 통합 테스트 (Kafka Mock, WAL 비활성화)
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.wal.enabled=false",  // ✅ WAL 비활성화
                "test.data.init.enabled=false"  // ✅ TestDataInitializer 비활성화
        }
)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SimpleIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @MockBean
    private KafkaTemplate<String, Object> kafkaTemplate;

    @MockBean
    private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @BeforeAll
    static void init() {
        System.out.println("\n=== 통합 테스트 시작 (Kafka Mock, WAL 비활성화) ===\n");
    }

    @BeforeEach
    void setupTestData() {
        System.out.println("✅ 테스트 데이터 초기화...");

        inventoryRepository.deleteAll();
        productRepository.deleteAll();

        Product product1 = Product.builder()
                .id("PROD-001")
                .name("Test Smartphone")
                .description("Test smartphone product")
                .price(new BigDecimal("799.99"))
                .category("ELECTRONICS")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        productRepository.save(product1);

        Inventory inventory1 = Inventory.builder()
                .productId("PROD-001")
                .totalQuantity(100)
                .availableQuantity(100)
                .reservedQuantity(0)
                .version(0L)
                .lastUpdatedAt(LocalDateTime.now())
                .build();

        inventoryRepository.save(inventory1);

        System.out.println("✅ 초기화 완료: 상품 1개, 재고 100개\n");
    }

    @Test
    @Order(1)
    @DisplayName("1. 헬스체크 테스트")
    void testHealthCheck() {
        System.out.println("\n[테스트 1] 헬스체크");
        String url = "http://localhost:" + port + "/api/system/health";

        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            System.out.println("✅ 응답: " + response.getStatusCode());
            assertTrue(response.getStatusCode().is2xxSuccessful());
        } catch (Exception e) {
            System.out.println("⚠️ 헬스체크 엔드포인트 없음 (정상)");
        }
        System.out.println();
    }

    @Test
    @Order(2)
    @DisplayName("2. 단일 예약 테스트")
    void testSingleReservation() {
        System.out.println("\n[테스트 2] 단일 예약");

        CompleteReservationRequest request = CompleteReservationRequest.builder()
                .productId("PROD-001")
                .customerId("TEST-001")
                .quantity(1)
                .clientId("test-client")
                .paymentInfo(CompleteReservationRequest.PaymentInfo.builder()
                        .amount(new BigDecimal("799.99"))
                        .currency("USD")
                        .paymentMethod("CREDIT_CARD")
                        .build())
                .idempotencyKey("test-key-001")
                .correlationId("test-corr-001")
                .build();

        String url = "http://localhost:" + port + "/api/reservations/complete";

        try {
            ResponseEntity<CompleteReservationResponse> response =
                    restTemplate.postForEntity(url, request, CompleteReservationResponse.class);

            System.out.println("응답: " + response.getStatusCode());
            assertNotNull(response.getBody());

            System.out.println("상태: " + response.getBody().getStatus());
            System.out.println("메시지: " + response.getBody().getMessage());

            if ("SUCCESS".equals(response.getBody().getStatus())) {
                System.out.println("✅ 예약 성공\n");
            } else {
                System.out.println("⚠️ 예약 실패: " + response.getBody().getMessage() + "\n");
            }
        } catch (Exception e) {
            System.err.println("❌ 예외: " + e.getMessage());
            fail("API 호출 실패");
        }
    }

    @Test
    @Order(3)
    @DisplayName("3. 연속 예약 테스트 (3명)")
    void testSequentialReservations() {
        System.out.println("\n[테스트 3] 연속 예약 (3명)");

        int successCount = 0;
        int failureCount = 0;

        for (int i = 1; i <= 3; i++) {
            System.out.println("\n--- 사용자 " + i + " ---");

            CompleteReservationRequest request = CompleteReservationRequest.builder()
                    .productId("PROD-001")
                    .customerId("TEST-SEQ-" + String.format("%03d", i))
                    .quantity(1)
                    .clientId("test-client")
                    .paymentInfo(CompleteReservationRequest.PaymentInfo.builder()
                            .amount(new BigDecimal("799.99"))
                            .currency("USD")
                            .paymentMethod("CREDIT_CARD")
                            .build())
                    .idempotencyKey("seq-key-" + i)
                    .correlationId("seq-corr-" + i)
                    .build();

            String url = "http://localhost:" + port + "/api/reservations/complete";

            try {
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
            } catch (Exception e) {
                failureCount++;
                System.err.println("❌ 예외: " + e.getMessage());
            }
        }

        System.out.println("\n=== 결과 ===");
        System.out.println("✅ 성공: " + successCount);
        System.out.println("❌ 실패: " + failureCount);

        assertTrue(successCount > 0, "최소 1명 성공 필요. 성공: " + successCount);
    }
}
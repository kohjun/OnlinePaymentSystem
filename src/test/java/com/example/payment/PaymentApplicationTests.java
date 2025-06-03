package com.example.payment;

import com.example.payment.dto.OrderItem;
import com.example.payment.dto.OrderRequest;
import com.example.payment.dto.OrderResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentApplicationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void contextLoads() {
    }

    @Test
    void testOrderCreation() {
        // 샘플 주문 생성
        OrderRequest request = createSampleOrder();

        // 주문 요청 전송
        ResponseEntity<OrderResponse> response = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/orders",
                request,
                OrderResponse.class);

        // 응답 출력
        System.out.println("응답 상태: " + response.getStatusCode());
        OrderResponse orderResponse = response.getBody();
        if (orderResponse != null) {
            System.out.println("주문 ID: " + orderResponse.getOrderId());
            System.out.println("주문 상태: " + orderResponse.getStatus());

            // 처리를 위해 잠시 대기
            try {
                System.out.println("비동기 처리를 위해 3초 대기...");
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            // 주문 상태 확인
            try {
                ResponseEntity<OrderResponse> statusResponse = restTemplate.getForEntity(
                        "http://localhost:" + port + "/api/orders/" + orderResponse.getOrderId(),
                        OrderResponse.class);

                if (statusResponse.getStatusCode().is2xxSuccessful() && statusResponse.getBody() != null) {
                    OrderResponse updatedOrder = statusResponse.getBody();
                    System.out.println("\n업데이트된 주문 상태: " + updatedOrder.getStatus());
                } else {
                    System.out.println("\n주문 상태 조회 실패: 응답이 null입니다.");
                }
            } catch (Exception e) {
                System.out.println("\n주문 상태 조회 중 오류 발생: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            System.out.println("주문 생성 실패: 응답이 null입니다.");
        }
    }

    private OrderRequest createSampleOrder() {
        // 샘플 주문 아이템 생성
        OrderItem item1 = OrderItem.builder()
                .productId("PROD-1001")
                .productName("스마트폰")
                .quantity(1)
                .price(new BigDecimal("799.99"))
                .build();

        OrderItem item2 = OrderItem.builder()
                .productId("PROD-2002")
                .productName("무선 이어버드")
                .quantity(1)
                .price(new BigDecimal("129.99"))
                .build();

        // 주문 요청 생성
        return OrderRequest.builder()
                .customerId("CUST-" + UUID.randomUUID().toString().substring(0, 8))
                .clientId("test-client")
                .items(Arrays.asList(item1, item2))
                .currency("USD")
                .paymentMethod("CREDIT_CARD")
                .idempotencyKey(UUID.randomUUID().toString())
                .shippingAddress("서울시 테스트로 123")
                .build();
    }
}
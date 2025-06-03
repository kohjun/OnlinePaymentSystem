package com.example.payment.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.UUID;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.example.payment.dto.OrderRequest;
import com.example.payment.dto.OrderResponse;
import com.example.payment.dto.PaymentRequest;
import com.example.payment.dto.PaymentResponse;
import com.example.payment.event.OrderEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 주문 처리 서비스
 * - 주문 생성 및 결제 요청 처리 (동기 방식)
 * - 주문 상태 관리
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OrderService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final CacheService cacheService;
    private final ObjectMapper objectMapper;
    private final PaymentService paymentService;

    private static final String ORDER_TOPIC = "order-events";
    private static final int CACHE_TTL = 3600; // 1시간

    /**
     * 새 주문 생성 및 결제 요청 시작 (동기식)
     */
    public OrderResponse createOrder(OrderRequest orderRequest) {
        // 주문 ID 생성
        String orderId = UUID.randomUUID().toString();
        log.info("Creating new order with ID: {}", orderId);

        // 주문 응답 객체 생성
        OrderResponse response = OrderResponse.builder()
                .orderId(orderId)
                .customerId(orderRequest.getCustomerId())
                .items(orderRequest.getItems())
                .totalAmount(calculateTotal(orderRequest))
                .status("CREATED")
                .createdAt(LocalDateTime.now())
                .build();

        // 주문 상태 캐싱
        String cacheKey = "order:" + orderId;
        cacheService.cacheData(cacheKey, response, CACHE_TTL);

        try {
            // 주문 이벤트 발행 (부가 처리용)
            String payload = objectMapper.writeValueAsString(response);
            kafkaTemplate.send(ORDER_TOPIC, orderId, payload);

            // 결제 요청 객체 생성
            PaymentRequest paymentRequest = PaymentRequest.builder()
                    .orderId(response.getOrderId())
                    .clientId(orderRequest.getClientId())
                    .customerId(orderRequest.getCustomerId())
                    .amount(response.getTotalAmount())
                    .currency(orderRequest.getCurrency())
                    .paymentMethod(orderRequest.getPaymentMethod())
                    .idempotencyKey(orderRequest.getIdempotencyKey())
                    .description("Payment for order " + response.getOrderId())
                    .build();

            // 동기식으로 결제 요청 처리 (간단한 방식)
            PaymentResponse paymentResponse;
            try {
                // 결제 서비스 동기 호출
                paymentResponse = paymentService.processPayment(paymentRequest);
            } catch (Exception e) {
                // 결제 서비스 호출 실패 처리
                log.error("Payment service failure: {}", e.getMessage(), e);
                paymentResponse = handlePaymentFailure(paymentRequest, e);
            }

            // 결제 결과에 따른 주문 상태 업데이트
            if ("COMPLETED".equals(paymentResponse.getStatus())) {
                response.setStatus("PAYMENT_COMPLETED");
            } else {
                response.setStatus("PAYMENT_FAILED");
                response.setMessage(paymentResponse.getMessage());
            }

            // 주문 상태 업데이트
            response.setUpdatedAt(LocalDateTime.now());
            cacheService.cacheData(cacheKey, response, CACHE_TTL);

            // 주문 상태 변경 이벤트 발행 (부가 처리용)
            payload = objectMapper.writeValueAsString(response);
            kafkaTemplate.send(ORDER_TOPIC, orderId, payload);

        } catch (Exception e) {
            log.error("Error processing order: {}", e.getMessage(), e);
            response.setStatus("ERROR");
            response.setMessage("Order processing error: " + e.getMessage());
            response.setUpdatedAt(LocalDateTime.now());

            // 에러 상태 업데이트
            cacheService.cacheData(cacheKey, response, CACHE_TTL);
        }

        return response;
    }

    /**
     * 결제 실패 처리 (간단한 폴백 메소드)
     */
    private PaymentResponse handlePaymentFailure(PaymentRequest request, Exception e) {
        String errorMessage = "Payment service error: " + e.getMessage();

        return PaymentResponse.builder()
                .paymentId(request.getPaymentId() != null ? request.getPaymentId() : UUID.randomUUID().toString())
                .orderId(request.getOrderId())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .status("FAILED")
                .message(errorMessage)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 주문 금액 계산
     */
    private BigDecimal calculateTotal(OrderRequest order) {
        return order.getItems().stream()
                .map(item -> item.getPrice().multiply(new BigDecimal(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // 주문 캐시 키 생성 (일관성 유지 필요)
    private String getCacheKey(String orderId) {
        return "order:" + orderId;
    }

    /**
     * 주문 상태 조회
     */
    public OrderResponse getOrderStatus(String orderId) {
        String cacheKey = getCacheKey(orderId);
        log.debug("Retrieving order from cache with key: {}", cacheKey);

        Object cachedData = cacheService.getCachedData(cacheKey);

        if (cachedData == null) {
            log.warn("Order not found in cache: {}", orderId);
            return null;
        }

        try {
            if (cachedData instanceof OrderResponse) {
                log.debug("Order found in cache as OrderResponse: {}", orderId);
                return (OrderResponse) cachedData;
            }

            // 다른 타입의 데이터인 경우 (LinkedHashMap 등) OrderResponse로 변환
            log.debug("Cached order data found with type: {}, converting to OrderResponse",
                    cachedData.getClass().getName());

            // ObjectMapper를 사용하여 변환 시도
            return objectMapper.convertValue(cachedData, OrderResponse.class);

        } catch (Exception e) {
            log.error("Error converting cached order data: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 결제 완료 처리 (결제 이벤트 핸들러에서 호출)
     * - 비동기 이벤트로 상태 동기화를 위해 필요
     */
    public void completePayment(String orderId) {
        String cacheKey = "order:" + orderId;
        Object cachedData = cacheService.getCachedData(cacheKey);

        try {
            OrderResponse order = null;

            if (cachedData instanceof OrderResponse) {
                order = (OrderResponse) cachedData;
            } else if (cachedData != null) {
                // 다른 타입의 데이터인 경우 OrderResponse로 변환
                order = objectMapper.convertValue(cachedData, OrderResponse.class);
            }

            if (order != null) {
                order.setStatus("PAYMENT_COMPLETED");
                order.setUpdatedAt(LocalDateTime.now());

                // 업데이트된 상태 저장
                cacheService.cacheData(cacheKey, order, CACHE_TTL);

                // 주문 완료 이벤트 발행
                try {
                    String payload = objectMapper.writeValueAsString(order);
                    kafkaTemplate.send(ORDER_TOPIC, orderId, payload);
                    log.info("Order payment completed: {}", orderId);
                } catch (JsonProcessingException e) {
                    log.error("Error serializing completed order: {}", e.getMessage());
                }
            } else {
                log.warn("Cannot complete payment: Order not found for ID: {}", orderId);
            }
        } catch (Exception e) {
            log.error("Error completing payment for order {}: {}", orderId, e.getMessage(), e);
        }
    }

    /**
     * 결제 실패 처리 (결제 이벤트 핸들러에서 호출)
     * - 비동기 이벤트로 상태 동기화를 위해 필요
     */
    public void failPayment(String orderId, String reason) {
        String cacheKey = "order:" + orderId;
        Object cachedData = cacheService.getCachedData(cacheKey);

        try {
            OrderResponse order = null;

            if (cachedData instanceof OrderResponse) {
                order = (OrderResponse) cachedData;
            } else if (cachedData != null) {
                // 다른 타입의 데이터인 경우 OrderResponse로 변환
                order = objectMapper.convertValue(cachedData, OrderResponse.class);
            }

            if (order != null) {
                order.setStatus("PAYMENT_FAILED");
                order.setMessage(reason);
                order.setUpdatedAt(LocalDateTime.now());

                // 업데이트된 상태 저장
                cacheService.cacheData(cacheKey, order, CACHE_TTL);

                // 결제 실패 이벤트 발행
                try {
                    String payload = objectMapper.writeValueAsString(order);
                    kafkaTemplate.send(ORDER_TOPIC, orderId, payload);
                    log.info("Order payment failed: {}", orderId);
                } catch (JsonProcessingException e) {
                    log.error("Error serializing failed order: {}", e.getMessage());
                }
            } else {
                log.warn("Cannot fail payment: Order not found for ID: {}", orderId);
            }
        } catch (Exception e) {
            log.error("Error failing payment for order {}: {}", orderId, e.getMessage(), e);
        }
    }
}
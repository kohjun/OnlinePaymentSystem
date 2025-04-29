package com.example.payment.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.example.payment.dto.OrderRequest;
import com.example.payment.dto.OrderResponse;
import com.example.payment.dto.PaymentRequest;
import com.example.payment.event.OrderEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 주문 처리 서비스
 * - 주문 생성 및 결제 요청 발행
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
     * 새 주문 생성 및 결제 요청 시작
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
            // 주문 이벤트 발행
            String payload = objectMapper.writeValueAsString(response);
            kafkaTemplate.send(ORDER_TOPIC, orderId, payload);

            // 결제 요청 생성 및 발행
            initiatePayment(orderRequest, response);

        } catch (JsonProcessingException e) {
            log.error("Error serializing order: {}", e.getMessage());
            response.setStatus("ERROR");

            // 에러 상태 업데이트
            cacheService.cacheData(cacheKey, response, CACHE_TTL);
        }

        return response;
    }

    /**
     * 주문에 대한 결제 요청 시작
     */
    private void initiatePayment(OrderRequest orderRequest, OrderResponse response) {
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

        // 결제 서비스에 요청 전달
        paymentService.initiatePayment(paymentRequest);

        // 주문 상태 업데이트
        response.setStatus("PAYMENT_PENDING");
        String cacheKey = "order:" + response.getOrderId();
        cacheService.cacheData(cacheKey, response, CACHE_TTL);
    }

    /**
     * 주문 금액 계산
     */
    private BigDecimal calculateTotal(OrderRequest order) {
        return order.getItems().stream()
                .map(item -> item.getPrice().multiply(new BigDecimal(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 주문 상태 조회
     */
    public OrderResponse getOrderStatus(String orderId) {
        String cacheKey = "order:" + orderId;
        Object cachedData = cacheService.getCachedData(cacheKey);

        if (cachedData instanceof OrderResponse) {
            return (OrderResponse) cachedData;
        }

        // 캐시에서 찾지 못한 경우 (실제 구현에서는 DB 조회 필요)
        log.warn("Order not found in cache: {}", orderId);
        return null;
    }

    /**
     * 결제 완료 처리 (결제 이벤트 핸들러에서 호출)
     */
    public void completePayment(String orderId) {
        String cacheKey = "order:" + orderId;
        Object cachedData = cacheService.getCachedData(cacheKey);

        if (cachedData instanceof OrderResponse) {
            OrderResponse order = (OrderResponse) cachedData;
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
        }
    }

    /**
     * 결제 실패 처리 (결제 이벤트 핸들러에서 호출)
     */
    public void failPayment(String orderId, String reason) {
        String cacheKey = "order:" + orderId;
        Object cachedData = cacheService.getCachedData(cacheKey);

        if (cachedData instanceof OrderResponse) {
            OrderResponse order = (OrderResponse) cachedData;
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
        }
    }
}
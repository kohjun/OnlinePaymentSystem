/**
 * ========================================
 * 3. OrderService (주문 생성 및 관리 전담)
 * ========================================
 */
package com.example.payment.application.service;

import com.example.payment.domain.exception.OrderException;
import com.example.payment.domain.model.common.Money;
import com.example.payment.domain.model.order.Order;
import com.example.payment.domain.model.order.OrderStatus;
import com.example.payment.infrastructure.buffer.WriteBufferService;
import com.example.payment.infrastructure.buffer.OrderWriteCommand;

import com.example.payment.infrastructure.persistance.redis.repository.CacheService;
import com.example.payment.infrastructure.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderService {

    private final WriteBufferService writeBufferService;
    private final CacheService cacheService;

    /**
     * 주문 생성 - 도메인 객체만 반환
     */
    public Order createOrder(String customerId, String productId, Integer quantity,
                             BigDecimal amount, String currency, String reservationId) {

        log.info("Creating order: customerId={}, productId={}, reservationId={}",
                customerId, productId, reservationId);

        try {
            String orderId = IdGenerator.generateOrderId();

            Order order = Order.builder()
                    .orderId(orderId)
                    .customerId(customerId)
                    .productId(productId)
                    .quantity(quantity)
                    .amount(Money.krw(amount))
                    .currency(currency)
                    .reservationId(reservationId)
                    .status(OrderStatus.valueOf("CREATED"))
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            // Write-Through: 즉시 캐시 저장
            String cacheKey = "order:" + orderId;
            cacheService.cacheData(cacheKey, order, 86400); //하루

            // Buffer: DB 쓰기는 비동기
            OrderWriteCommand writeCommand = new OrderWriteCommand(order);
            writeBufferService.enqueue(writeCommand);

            log.info("Order created successfully: orderId={}", orderId);
            return order;

        } catch (Exception e) {
            log.error("Error creating order: customerId={}, reservationId={}", customerId, reservationId, e);
            throw new OrderException("주문 생성 중 오류 발생", e);
        }
    }

    /**
     * 주문 취소
     */
    public boolean cancelOrder(String orderId, String customerId) {
        try {
            log.info("Cancelling order: orderId={}, customerId={}", orderId, customerId);

            // 캐시에서 주문 조회
            String cacheKey = "order:" + orderId;
            Object cachedData = cacheService.getCachedData(cacheKey);

            if (cachedData != null) {
                Order order = (Order) cachedData;

                // 상태 변경
                order.setStatus("CANCELLED");
                order.setUpdatedAt(LocalDateTime.now());

                // 캐시 업데이트
                cacheService.cacheData(cacheKey, order, 86400);

                log.info("Order cancelled: orderId={}", orderId);
                return true;
            }

            return false;

        } catch (Exception e) {
            log.error("Error cancelling order: orderId={}", orderId, e);
            return false;
        }
    }

    /**
     * 주문 조회
     */
    public Order getOrder(String orderId) {
        try {
            String cacheKey = "order:" + orderId;
            Object cachedData = cacheService.getCachedData(cacheKey);

            if (cachedData != null) {
                return (Order) cachedData;
            }

            return null;

        } catch (Exception e) {
            log.error("Error getting order: orderId={}", orderId, e);
            return null;
        }
    }
}

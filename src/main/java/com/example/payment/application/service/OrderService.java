package com.example.payment.application.service;

import com.example.payment.domain.exception.OrderException;
import com.example.payment.domain.entity.OrderRecord;
import com.example.payment.domain.model.common.Money;
import com.example.payment.domain.model.order.Order;
import com.example.payment.domain.model.order.OrderStatus;
import com.example.payment.domain.repository.OrderRecordRepository;
import com.example.payment.infrastructure.persistence.redis.repository.CacheService;
import com.example.payment.infrastructure.util.IdGenerator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 주문 서비스
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OrderService {

    private final CacheService cacheService;
    private final OrderRecordRepository orderRecordRepository;

    private static final int ORDER_CACHE_TTL_SECONDS = 86400; // 24시간

    public OrderCreationResult createOrder(
            String transactionId,
            String customerId,
            String productId,
            Integer quantity,
            BigDecimal amount,
            String currency,
            String reservationId) {

        log.info("[Phase 1] Creating order: txId={}, customerId={}, productId={}, reservationId={}",
                transactionId, customerId, productId, reservationId);

        String orderId = IdGenerator.generateOrderId();

        try {
            Order order = Order.builder()
                    .orderId(orderId)
                    .customerId(customerId)
                    .productId(productId)
                    .quantity(quantity)
                    .amount(Money.of(amount, currency))
                    .currency(currency)
                    .reservationId(reservationId)
                    .status(OrderStatus.CREATED)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            orderRecordRepository.save(OrderRecord.builder()
                    .orderId(orderId)
                    .customerId(customerId)
                    .productId(productId)
                    .reservationId(reservationId)
                    .quantity(quantity)
                    .amount(amount)
                    .currency(currency)
                    .status(OrderStatus.CREATED.name())
                    .createdAt(order.getCreatedAt())
                    .build());

            String cacheKey = "order:" + orderId;
            // cacheData는 cacheObject의 alias이며 String(JSON)으로 저장
            cacheService.cacheData(cacheKey, order, ORDER_CACHE_TTL_SECONDS);

            String txMappingKey = "tx_order:" + transactionId;
            cacheService.cacheData(txMappingKey, orderId, ORDER_CACHE_TTL_SECONDS);

            log.info("[Phase 1] Order created successfully: txId={}, orderId={}",
                    transactionId, orderId);

            return new OrderCreationResult(order);

        } catch (Exception e) {
            log.error("[Phase 1] Error creating order: txId={}, customerId={}, reservationId={}",
                    transactionId, customerId, reservationId, e);

            throw new OrderException("주문 생성 실패", e);
        }
    }

    public boolean markOrderAsPaid(
            String transactionId,
            String orderId,
            String paymentId) {

        log.info("[Phase 2] Marking order as paid: txId={}, orderId={}, paymentId={}",
                transactionId, orderId, paymentId);

        try {
            Order order = getOrder(orderId);
            if (order == null) {
                log.warn("Order not found: orderId={}", orderId);
                return false;
            }

            order.markAsPaid(paymentId);
            orderRecordRepository.findById(orderId).ifPresent(record -> {
                record.setPaymentId(paymentId);
                record.setStatus(OrderStatus.PAID.name());
                orderRecordRepository.save(record);
            });

            String cacheKey = "order:" + orderId;
            cacheService.cacheData(cacheKey, order, ORDER_CACHE_TTL_SECONDS);

            log.info(" [Phase 2] Order marked as paid: txId={}, orderId={}",
                    transactionId, orderId);
            return true;

        } catch (Exception e) {
            log.error(" [Phase 2] Error marking order as paid: txId={}, orderId={}",
                    transactionId, orderId, e);

            return false;
        }
    }

    public boolean updateOrderStatus(
            String transactionId,
            String orderId,
            String newStatus,
            String reason) {
        try {
            log.info("Updating order status: txId={}, orderId={}, newStatus={}, reason={}",
                    transactionId, orderId, newStatus, reason);

            Order order = getOrder(orderId);
            if (order == null) {
                log.warn("Order not found: orderId={}", orderId);
                return false;
            }

            order.setStatus(OrderStatus.valueOf(newStatus));
            order.setUpdatedAt(LocalDateTime.now());
            orderRecordRepository.findById(orderId).ifPresent(record -> {
                record.setStatus(newStatus);
                orderRecordRepository.save(record);
            });

            String cacheKey = "order:" + orderId;
            cacheService.cacheData(cacheKey, order, ORDER_CACHE_TTL_SECONDS);

            log.info(" Order status updated: txId={}, orderId={}, {} -> {}",
                    transactionId, orderId, order.getStatus().name(), newStatus);
            return true;

        } catch (Exception e) {
            log.error(" Error updating order status: txId={}, orderId={}, newStatus={}",
                    transactionId, orderId, newStatus, e);

            return false;
        }
    }

    public boolean cancelOrder(String transactionId, String orderId, String customerId, String reason) {
        try {
            log.info("🟠 Cancelling order: txId={}, orderId={}, customerId={}, reason={}",
                    transactionId, orderId, customerId, reason);

            Order order = getOrder(orderId);
            if (order == null) {
                log.warn("Order not found: orderId={}", orderId);
                return false;
            }

            if (!customerId.equals(order.getCustomerId()) && !"SYSTEM".equals(customerId)) {
                log.warn("Customer ID mismatch: orderId={}, expected={}, actual={}",
                        orderId, order.getCustomerId(), customerId);
                return false;
            }

            if (!order.canBeCancelled()) {
                log.warn("Order cannot be cancelled: orderId={}, status={}",
                        orderId, order.getStatus());
                return false;
            }

            order.setStatus(OrderStatus.CANCELLED);
            order.setUpdatedAt(LocalDateTime.now());
            orderRecordRepository.findById(orderId).ifPresent(record -> {
                record.setStatus(OrderStatus.CANCELLED.name());
                orderRecordRepository.save(record);
            });

            String cacheKey = "order:" + orderId;
            cacheService.cacheData(cacheKey, order, ORDER_CACHE_TTL_SECONDS);

            log.info("Order cancelled: txId={}, orderId={}", transactionId, orderId);
            return true;

        } catch (Exception e) {
            log.error("Error cancelling order: txId={}, orderId={}", transactionId, orderId, e);

            return false;
        }
    }

    /**
     * 주문 조회
     * getCachedData (Hash 읽기) -> getCachedObject (String 읽기) 변경
     */
    public Order getOrder(String orderId) {
        try {
            String cacheKey = "order:" + orderId;
            // [수정] String(JSON)으로 저장된 객체를 읽어옵니다.
            Order cachedData = cacheService.getCachedObject(cacheKey, Order.class);

            if (cachedData != null) {
                log.debug("Order found in cache: orderId={}", orderId);
                return cachedData;
            }

            Order order = orderRecordRepository.findById(orderId)
                    .map(this::toDomainOrder)
                    .orElse(null);
            if (order != null) {
                cacheService.cacheData(cacheKey, order, ORDER_CACHE_TTL_SECONDS);
                log.debug("Order found in Postgres: orderId={}", orderId);
                return order;
            }

            log.debug("Order not found: orderId={}", orderId);
            return null;

        } catch (Exception e) {
            log.error("Error getting order: orderId={}", orderId, e);
            return null;
        }
    }

    public Order getOrderByReservationId(String reservationId) {
        try {
            return orderRecordRepository.findByReservationId(reservationId)
                    .map(this::toDomainOrder)
                    .orElse(null);
        } catch (Exception e) {
            log.error("Error getting order by reservation: reservationId={}", reservationId, e);
            return null;
        }
    }

    public List<Order> getOrdersByCustomerId(String customerId, int page, int size) {
        try {
            Pageable pageable = PageRequest.of(Math.max(page, 0), normalizePageSize(size));
            return orderRecordRepository.findByCustomerIdOrderByCreatedAtDesc(customerId, pageable)
                    .stream()
                    .map(this::toDomainOrder)
                    .toList();
        } catch (Exception e) {
            log.error("Error getting orders by customer: customerId={}", customerId, e);
            return List.of();
        }
    }

    // ===================================
    // Helper Methods
    // ===================================

    private Order toDomainOrder(OrderRecord record) {
        return Order.builder()
                .orderId(record.getOrderId())
                .customerId(record.getCustomerId())
                .productId(record.getProductId())
                .quantity(record.getQuantity())
                .amount(Money.of(record.getAmount(), record.getCurrency()))
                .currency(record.getCurrency())
                .reservationId(record.getReservationId())
                .paymentId(record.getPaymentId())
                .status(OrderStatus.valueOf(record.getStatus()))
                .createdAt(record.getCreatedAt())
                .updatedAt(record.getUpdatedAt())
                .build();
    }

    private int normalizePageSize(int size) {
        if (size <= 0) {
            return 10;
        }
        return Math.min(size, 100);
    }

    public static class OrderCreationResult {
        private final Order order;

        public OrderCreationResult(Order order) {
            this.order = order;
        }

        public Order getOrder() {
            return order;
        }
    }
}

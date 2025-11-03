package com.example.payment.application.service;

import com.example.payment.domain.exception.OrderException;
import com.example.payment.domain.model.common.Money;
import com.example.payment.domain.model.order.Order;
import com.example.payment.domain.model.order.OrderStatus;
import com.example.payment.infrastructure.persistence.redis.repository.CacheService;
import com.example.payment.infrastructure.persistence.wal.WalService;
import com.example.payment.infrastructure.util.IdGenerator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * ✅ 개선된 주문 서비스
 * [수정] getOrder의 캐시 읽기 방식을 getCachedObject로 변경
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OrderService {

    private final WalService walService;
    private final CacheService cacheService;

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
            String entityIds = buildEntityIdsJson(reservationId, orderId, null);
            String afterData = buildOrderJson(
                    orderId, customerId, productId, quantity,
                    amount, currency, reservationId, "CREATED"
            );

            String walLogId = walService.logOperationStart(
                    transactionId,
                    "ORDER_CREATE_START",
                    "orders",
                    entityIds,
                    afterData
            );

            log.debug("✅ WAL Phase 1 logged: txId={}, walLogId={}, orderId={}",
                    transactionId, walLogId, orderId);

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

            String cacheKey = "order:" + orderId;
            // cacheData는 cacheObject의 alias이며 String(JSON)으로 저장
            cacheService.cacheData(cacheKey, order, ORDER_CACHE_TTL_SECONDS);

            String txMappingKey = "tx_order:" + transactionId;
            cacheService.cacheData(txMappingKey, orderId, ORDER_CACHE_TTL_SECONDS);

            walService.logOperationComplete(
                    transactionId,
                    "ORDER_CREATE_COMPLETE",
                    "orders",
                    entityIds,
                    null,
                    afterData
            );
            walService.updateLogStatus(walLogId, "COMMITTED", "주문 생성 완료");

            log.info("[Phase 1] Order created successfully: txId={}, orderId={}",
                    transactionId, orderId);

            return new OrderCreationResult(order, walLogId);

        } catch (Exception e) {
            log.error("[Phase 1] Error creating order: txId={}, customerId={}, reservationId={}",
                    transactionId, customerId, reservationId, e);

            String entityIds = buildEntityIdsJson(reservationId, orderId, null);
            walService.logOperationFailure(
                    transactionId,
                    "ORDER_CREATE_ERROR",
                    "orders",
                    entityIds,
                    e.getMessage()
            );

            throw new OrderException("주문 생성 실패", e);
        }
    }

    public boolean markOrderAsPaid(
            String transactionId,
            String phase1LogId,
            String orderId,
            String paymentId) {

        log.info("[Phase 2] Marking order as paid: txId={}, orderId={}, paymentId={}, phase1LogId={}",
                transactionId, orderId, paymentId, phase1LogId);

        try {
            Order order = getOrder(orderId);
            if (order == null) {
                log.warn("Order not found: orderId={}", orderId);
                return false;
            }

            String entityIds = buildEntityIdsJson(order.getReservationId(), orderId, paymentId);
            String beforeData = buildOrderStatusJson(orderId, order.getStatus().name());
            String afterData = buildOrderStatusJson(orderId, "PAID");

            String walLogId = walService.logPhase2Start(
                    transactionId,
                    phase1LogId,
                    "ORDER_PAYMENT_START",
                    "orders",
                    entityIds,
                    beforeData,
                    afterData
            );

            log.debug(" WAL Phase 2 logged: txId={}, walLogId={}, phase1LogId={}",
                    transactionId, walLogId, phase1LogId);

            order.markAsPaid(paymentId);

            String cacheKey = "order:" + orderId;
            cacheService.cacheData(cacheKey, order, ORDER_CACHE_TTL_SECONDS);

            walService.logOperationComplete(
                    transactionId,
                    "ORDER_PAYMENT_COMPLETE",
                    "orders",
                    entityIds,
                    beforeData,
                    afterData
            );
            walService.updateLogStatus(walLogId, "COMMITTED", "주문 결제 완료");

            log.info(" [Phase 2] Order marked as paid: txId={}, orderId={}",
                    transactionId, orderId);
            return true;

        } catch (Exception e) {
            log.error(" [Phase 2] Error marking order as paid: txId={}, orderId={}",
                    transactionId, orderId, e);

            String entityIds = buildEntityIdsJson(null, orderId, paymentId);
            walService.logOperationFailure(
                    transactionId,
                    "ORDER_PAYMENT_ERROR",
                    "orders",
                    entityIds,
                    e.getMessage()
            );

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

            String oldStatus = order.getStatus().name();
            String entityIds = buildEntityIdsJson(order.getReservationId(), orderId, order.getPaymentId());
            String afterData = buildOrderStatusJson(orderId, newStatus);

            String walLogId = walService.logOperationStart(
                    transactionId,
                    "ORDER_STATUS_CHANGE",
                    "orders",
                    entityIds,
                    afterData
            );

            order.setStatus(OrderStatus.valueOf(newStatus));
            order.setUpdatedAt(LocalDateTime.now());

            String cacheKey = "order:" + orderId;
            cacheService.cacheData(cacheKey, order, ORDER_CACHE_TTL_SECONDS);

            String beforeData = buildOrderStatusJson(orderId, oldStatus);
            walService.logOperationComplete(
                    transactionId,
                    "ORDER_STATUS_CHANGE_COMPLETE",
                    "orders",
                    entityIds,
                    beforeData,
                    afterData
            );
            walService.updateLogStatus(walLogId, "COMMITTED", "상태 변경 완료: " + reason);

            log.info(" Order status updated: txId={}, orderId={}, {} -> {}",
                    transactionId, orderId, oldStatus, newStatus);
            return true;

        } catch (Exception e) {
            log.error(" Error updating order status: txId={}, orderId={}, newStatus={}",
                    transactionId, orderId, newStatus, e);

            String entityIds = buildEntityIdsJson(null, orderId, null);
            walService.logOperationFailure(
                    transactionId,
                    "ORDER_STATUS_CHANGE_ERROR",
                    "orders",
                    entityIds,
                    e.getMessage()
            );

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

            String entityIds = buildEntityIdsJson(order.getReservationId(), orderId, order.getPaymentId());
            String afterData = buildOrderStatusJson(orderId, "CANCELLED");

            String walLogId = walService.logOperationStart(
                    transactionId,
                    "ORDER_CANCEL_START",
                    "orders",
                    entityIds,
                    afterData
            );

            String oldStatus = order.getStatus().name();
            order.setStatus(OrderStatus.CANCELLED);
            order.setUpdatedAt(LocalDateTime.now());

            String cacheKey = "order:" + orderId;
            cacheService.cacheData(cacheKey, order, ORDER_CACHE_TTL_SECONDS);

            String beforeData = buildOrderStatusJson(orderId, oldStatus);
            walService.logOperationComplete(
                    transactionId,
                    "ORDER_CANCEL_COMPLETE",
                    "orders",
                    entityIds,
                    beforeData,
                    afterData
            );
            walService.updateLogStatus(walLogId, "COMMITTED", "주문 취소 완료: " + reason);

            log.info("Order cancelled: txId={}, orderId={}", transactionId, orderId);
            return true;

        } catch (Exception e) {
            log.error("Error cancelling order: txId={}, orderId={}", transactionId, orderId, e);

            String entityIds = buildEntityIdsJson(null, orderId, null);
            walService.logOperationFailure(
                    transactionId,
                    "ORDER_CANCEL_ERROR",
                    "orders",
                    entityIds,
                    e.getMessage()
            );

            return false;
        }
    }

    /**
     * 주문 조회
     * [수정] getCachedData (Hash 읽기) -> getCachedObject (String 읽기) 변경
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

            log.debug("Order not found: orderId={}", orderId);
            return null;

        } catch (Exception e) {
            log.error("Error getting order: orderId={}", orderId, e);
            return null;
        }
    }

    // ===================================
    // Helper Methods
    // ===================================

    private String buildEntityIdsJson(String reservationId, String orderId, String paymentId) {
        return String.format(
                "{\"reservationId\":\"%s\",\"orderId\":\"%s\",\"paymentId\":\"%s\"}",
                reservationId != null ? reservationId : "null",
                orderId != null ? orderId : "null",
                paymentId != null ? paymentId : "null"
        );
    }

    private String buildOrderJson(String orderId, String customerId, String productId,
                                  Integer quantity, BigDecimal amount, String currency,
                                  String reservationId, String status) {
        return String.format(
                "{\"orderId\":\"%s\",\"customerId\":\"%s\",\"productId\":\"%s\"," +
                        "\"quantity\":%d,\"amount\":%s,\"currency\":\"%s\"," +
                        "\"reservationId\":\"%s\",\"status\":\"%s\",\"timestamp\":\"%s\"}",
                orderId, customerId, productId, quantity, amount, currency,
                reservationId, status, LocalDateTime.now()
        );
    }

    private String buildOrderStatusJson(String orderId, String status) {
        return String.format(
                "{\"orderId\":\"%s\",\"status\":\"%s\",\"timestamp\":\"%s\"}",
                orderId, status, LocalDateTime.now()
        );
    }

    public static class OrderCreationResult {
        private final Order order;
        private final String phase1WalLogId;

        public OrderCreationResult(Order order, String phase1WalLogId) {
            this.order = order;
            this.phase1WalLogId = phase1WalLogId;
        }

        public Order getOrder() {
            return order;
        }

        public String getPhase1WalLogId() {
            return phase1WalLogId;
        }
    }
}
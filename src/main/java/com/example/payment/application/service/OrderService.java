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
 * ✅ 개선된 주문 서비스 - 트랜잭션 ID 정합성 강화
 *
 * 주요 개선사항:
 * 1. 트랜잭션 ID를 외부에서 주입받아 WAL 일관성 보장
 * 2. Phase 1 WAL 로그 ID를 반환하여 Phase 2와 연결
 * 3. 엔티티 ID(reservationId, orderId, paymentId) 추적 강화
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OrderService {

    private final WalService walService;
    private final CacheService cacheService;

    private static final int ORDER_CACHE_TTL_SECONDS = 86400; // 24시간

    /**
     * ✅ 개선: 주문 생성 (Phase 1) - 트랜잭션 ID와 WAL 로그 ID 반환
     *
     * @param transactionId 비즈니스 트랜잭션 ID
     * @param customerId 고객 ID
     * @param productId 상품 ID
     * @param quantity 수량
     * @param amount 금액
     * @param currency 통화
     * @param reservationId 예약 ID (연결)
     * @return OrderCreationResult (주문 + WAL 로그 ID)
     */
    public OrderCreationResult createOrder(
            String transactionId,  // ✅ 트랜잭션 ID 추가
            String customerId,
            String productId,
            Integer quantity,
            BigDecimal amount,
            String currency,
            String reservationId) {

        log.info("🔵 [Phase 1] Creating order: txId={}, customerId={}, productId={}, reservationId={}",
                transactionId, customerId, productId, reservationId);

        String orderId = IdGenerator.generateOrderId();

        try {
            // ===================================
            // 1. WAL Phase 1 시작 로그
            // ===================================
            String entityIds = buildEntityIdsJson(reservationId, orderId, null);
            String afterData = buildOrderJson(
                    orderId, customerId, productId, quantity,
                    amount, currency, reservationId, "CREATED"
            );

            String walLogId = walService.logOperationStart(
                    transactionId,  // ✅ 트랜잭션 ID 전달
                    "ORDER_CREATE_START",
                    "orders",
                    entityIds,
                    afterData
            );

            log.debug("✅ WAL Phase 1 logged: txId={}, walLogId={}, orderId={}",
                    transactionId, walLogId, orderId);

            // ===================================
            // 2. 도메인 객체 생성
            // ===================================
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

            // ===================================
            // 3. 캐시 저장 (메타데이터 포함)
            // ===================================
            String cacheKey = "order:" + orderId;
            cacheService.cacheData(cacheKey, order, ORDER_CACHE_TTL_SECONDS);

            // 트랜잭션 ID 매핑 캐시 (복구 시 활용)
            String txMappingKey = "tx_order:" + transactionId;
            cacheService.cacheData(txMappingKey, orderId, ORDER_CACHE_TTL_SECONDS);

            // ===================================
            // 4. WAL Phase 1 완료 로그
            // ===================================
            walService.logOperationComplete(
                    transactionId,
                    "ORDER_CREATE_COMPLETE",
                    "orders",
                    entityIds,
                    null,
                    afterData
            );
            walService.updateLogStatus(walLogId, "COMMITTED", "주문 생성 완료");

            log.info("✅ [Phase 1] Order created successfully: txId={}, orderId={}",
                    transactionId, orderId);

            // ✅ Phase 1 WAL 로그 ID와 함께 반환 (Phase 2 연결용)
            return new OrderCreationResult(order, walLogId);

        } catch (Exception e) {
            log.error("❌ [Phase 1] Error creating order: txId={}, customerId={}, reservationId={}",
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

    /**
     * ✅ 개선: 주문 결제 완료 처리 (Phase 2) - Phase 1 로그와 연결
     *
     * @param transactionId 비즈니스 트랜잭션 ID
     * @param phase1LogId Phase 1의 WAL Entry ID (연결용)
     * @param orderId 주문 ID
     * @param paymentId 결제 ID
     * @return 성공 여부
     */
    public boolean markOrderAsPaid(
            String transactionId,  // ✅ 트랜잭션 ID 추가
            String phase1LogId,    // ✅ Phase 1 로그 ID 추가
            String orderId,
            String paymentId) {

        log.info("🟢 [Phase 2] Marking order as paid: txId={}, orderId={}, paymentId={}, phase1LogId={}",
                transactionId, orderId, paymentId, phase1LogId);

        try {
            // 1. 주문 조회
            Order order = getOrder(orderId);
            if (order == null) {
                log.warn("Order not found: orderId={}", orderId);
                return false;
            }

            // ===================================
            // 2. WAL Phase 2 시작 로그 (Phase 1과 연결)
            // ===================================
            String entityIds = buildEntityIdsJson(order.getReservationId(), orderId, paymentId);
            String beforeData = buildOrderStatusJson(orderId, order.getStatus().name());
            String afterData = buildOrderStatusJson(orderId, "PAID");

            String walLogId = walService.logPhase2Start(
                    transactionId,      // ✅ 동일한 트랜잭션 ID
                    phase1LogId,        // ✅ Phase 1 로그와 연결
                    "ORDER_PAYMENT_START",
                    "orders",
                    entityIds,
                    beforeData,
                    afterData
            );

            log.debug("✅ WAL Phase 2 logged: txId={}, walLogId={}, phase1LogId={}",
                    transactionId, walLogId, phase1LogId);

            // ===================================
            // 3. 주문 상태 업데이트
            // ===================================
            order.markAsPaid(paymentId);

            // ===================================
            // 4. 캐시 업데이트
            // ===================================
            String cacheKey = "order:" + orderId;
            cacheService.cacheData(cacheKey, order, ORDER_CACHE_TTL_SECONDS);

            // ===================================
            // 5. WAL Phase 2 완료 로그
            // ===================================
            walService.logOperationComplete(
                    transactionId,
                    "ORDER_PAYMENT_COMPLETE",
                    "orders",
                    entityIds,
                    beforeData,
                    afterData
            );
            walService.updateLogStatus(walLogId, "COMMITTED", "주문 결제 완료");

            log.info("✅ [Phase 2] Order marked as paid: txId={}, orderId={}",
                    transactionId, orderId);
            return true;

        } catch (Exception e) {
            log.error("❌ [Phase 2] Error marking order as paid: txId={}, orderId={}",
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

    /**
     * ✅ 개선: 주문 상태 변경 - 트랜잭션 ID 주입
     */
    public boolean updateOrderStatus(
            String transactionId,
            String orderId,
            String newStatus,
            String reason) {
        try {
            log.info("🟡 Updating order status: txId={}, orderId={}, newStatus={}, reason={}",
                    transactionId, orderId, newStatus, reason);

            // 1. 주문 조회
            Order order = getOrder(orderId);
            if (order == null) {
                log.warn("Order not found: orderId={}", orderId);
                return false;
            }

            String oldStatus = order.getStatus().name();

            // 2. WAL 로그
            String entityIds = buildEntityIdsJson(order.getReservationId(), orderId, order.getPaymentId());
            String afterData = buildOrderStatusJson(orderId, newStatus);

            String walLogId = walService.logOperationStart(
                    transactionId,
                    "ORDER_STATUS_CHANGE",
                    "orders",
                    entityIds,
                    afterData
            );

            // 3. 상태 변경
            order.setStatus(OrderStatus.valueOf(newStatus));
            order.setUpdatedAt(LocalDateTime.now());

            // 4. 캐시 업데이트
            String cacheKey = "order:" + orderId;
            cacheService.cacheData(cacheKey, order, ORDER_CACHE_TTL_SECONDS);

            // 5. WAL 완료
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

            log.info("✅ Order status updated: txId={}, orderId={}, {} -> {}",
                    transactionId, orderId, oldStatus, newStatus);
            return true;

        } catch (Exception e) {
            log.error("❌ Error updating order status: txId={}, orderId={}, newStatus={}",
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

    /**
     * ✅ 개선: 주문 취소 - 트랜잭션 ID 주입
     */
    public boolean cancelOrder(String transactionId, String orderId, String customerId, String reason) {
        try {
            log.info("🟠 Cancelling order: txId={}, orderId={}, customerId={}, reason={}",
                    transactionId, orderId, customerId, reason);

            // 1. 주문 조회
            Order order = getOrder(orderId);
            if (order == null) {
                log.warn("Order not found: orderId={}", orderId);
                return false;
            }

            // 2. 권한 확인
            if (!customerId.equals(order.getCustomerId()) && !"SYSTEM".equals(customerId)) {
                log.warn("Customer ID mismatch: orderId={}, expected={}, actual={}",
                        orderId, order.getCustomerId(), customerId);
                return false;
            }

            // 3. 취소 가능 여부 확인
            if (!order.canBeCancelled()) {
                log.warn("Order cannot be cancelled: orderId={}, status={}",
                        orderId, order.getStatus());
                return false;
            }

            // 4. WAL 로그
            String entityIds = buildEntityIdsJson(order.getReservationId(), orderId, order.getPaymentId());
            String afterData = buildOrderStatusJson(orderId, "CANCELLED");

            String walLogId = walService.logOperationStart(
                    transactionId,
                    "ORDER_CANCEL_START",
                    "orders",
                    entityIds,
                    afterData
            );

            // 5. 상태 변경
            String oldStatus = order.getStatus().name();
            order.setStatus(OrderStatus.CANCELLED);
            order.setUpdatedAt(LocalDateTime.now());

            // 6. 캐시 업데이트
            String cacheKey = "order:" + orderId;
            cacheService.cacheData(cacheKey, order, ORDER_CACHE_TTL_SECONDS);

            // 7. WAL 완료
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

            log.info("✅ Order cancelled: txId={}, orderId={}", transactionId, orderId);
            return true;

        } catch (Exception e) {
            log.error("❌ Error cancelling order: txId={}, orderId={}", transactionId, orderId, e);

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
     */
    public Order getOrder(String orderId) {
        try {
            String cacheKey = "order:" + orderId;
            Object cachedData = cacheService.getCachedData(cacheKey);

            if (cachedData != null) {
                log.debug("Order found in cache: orderId={}", orderId);

                if (cachedData instanceof Order) {
                    return (Order) cachedData;
                } else {
                    log.warn("Cached data is not Order type: orderId={}, actualType={}",
                            orderId, cachedData.getClass().getName());
                    cacheService.deleteCache(cacheKey);
                }
            }

            log.debug("Order not found: orderId={}", orderId);
            return null;

        } catch (Exception e) {
            log.error("Error getting order: orderId={}", orderId, e);
            return null;
        }
    }

    // ===================================
    // Helper Methods - 엔티티 ID 추적용 JSON 빌더
    // ===================================

    /**
     * ✅ 엔티티 ID들을 JSON 형태로 구성
     */
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

    // ===================================
    // Result Class - Phase 1 WAL 로그 ID 포함
    // ===================================

    /**
     * ✅ 주문 생성 결과 (주문 + WAL 로그 ID)
     * Phase 2에서 Phase 1과 연결하기 위해 필요
     */
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
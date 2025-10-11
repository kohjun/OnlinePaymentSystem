package com.example.payment.application.service;

import com.example.payment.domain.exception.OrderException;
import com.example.payment.domain.model.common.Money;
import com.example.payment.domain.model.order.Order;
import com.example.payment.domain.model.order.OrderStatus;
import com.example.payment.infrastructure.persistence.redis.repository.CacheService;
import com.example.payment.infrastructure.persistence.wal.WalService;
import com.example.payment.infrastructure.util.IdGenerator;
import com.example.payment.presentation.dto.response.OrderResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 주문 서비스 - 단일 책임 원칙 준수
 *
 * 🎯 단일 책임: 주문(Order) 생명주기 관리만 담당
 *
 * 담당 범위:
 * - 주문 생성
 * - 주문 상태 변경
 * - 주문 취소
 * - 주문 조회
 *
 * 담당하지 않음:
 * - 결제 처리 → PaymentProcessingService
 * - 재고 관리 → ReservationService, InventoryManagementService
 * - WAL 로그 → WalService (횡단 관심사)
 * - 캐싱 → CacheService (인프라)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OrderService {

    // 인프라 서비스들
    private final WalService walService;
    private final CacheService cacheService;

    // 캐시 TTL
    private static final int ORDER_CACHE_TTL_SECONDS = 86400; // 24시간

    /**
     * 주문 생성 (Phase 1)
     *
     * @param customerId 고객 ID
     * @param productId 상품 ID
     * @param quantity 수량
     * @param amount 금액
     * @param currency 통화
     * @param reservationId 예약 ID (연결)
     * @return 주문 도메인 객체
     */
    public Order createOrder(String customerId, String productId, Integer quantity,
                             BigDecimal amount, String currency, String reservationId) {

        log.info("Creating order: customerId={}, productId={}, reservationId={}",
                customerId, productId, reservationId);

        String orderId = IdGenerator.generateOrderId();

        try {
            // 1. WAL 시작 로그
            String walLogId = walService.logOperationStart(
                    "ORDER_CREATE_START",
                    "orders",
                    buildOrderJson(orderId, customerId, productId, quantity,
                            amount, currency, reservationId, "CREATED")
            );

            // 2. 도메인 객체 생성
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

            // 3. 캐시 저장
            String cacheKey = "order:" + orderId;
            cacheService.cacheData(cacheKey, order, ORDER_CACHE_TTL_SECONDS);

            // 4. WAL 완료 로그
            walService.logOperationComplete(
                    "ORDER_CREATE_COMPLETE",
                    "orders",
                    null,
                    buildOrderJson(orderId, customerId, productId, quantity,
                            amount, currency, reservationId, "CREATED")
            );
            walService.updateLogStatus(walLogId, "COMMITTED", "주문 생성 완료");

            log.info("Order created successfully: orderId={}", orderId);
            return order;

        } catch (Exception e) {
            log.error("Error creating order: customerId={}, reservationId={}",
                    customerId, reservationId, e);

            walService.logOperationFailure(
                    "ORDER_CREATE_ERROR",
                    "orders",
                    e.getMessage()
            );

            throw new OrderException("주문 생성 실패", e);
        }
    }

    /**
     * 주문 결제 완료 처리 (Phase 2)
     *
     * @param orderId 주문 ID
     * @param paymentId 결제 ID
     * @return 성공 여부
     */
    public boolean markOrderAsPaid(String orderId, String paymentId) {

        log.info("Marking order as paid: orderId={}, paymentId={}", orderId, paymentId);

        try {
            // 1. 주문 조회
            Order order = getOrder(orderId);
            if (order == null) {
                log.warn("Order not found: orderId={}", orderId);
                return false;
            }

            // 2. WAL Phase 2 시작 로그
            String walLogId = walService.logPhase2Start(
                    "ORDER_PAYMENT_START",
                    null, // relatedLogId는 필요시 추가
                    buildOrderStatusJson(orderId, order.getStatus().name()),
                    buildOrderStatusJson(orderId, "PAID")
            );

            // 3. 주문 상태 업데이트
            order.markAsPaid(paymentId);

            // 4. 캐시 업데이트
            String cacheKey = "order:" + orderId;
            cacheService.cacheData(cacheKey, order, ORDER_CACHE_TTL_SECONDS);

            // 5. WAL 완료 로그
            walService.updateLogStatus(walLogId, "COMMITTED", "주문 결제 완료");

            log.info("Order marked as paid: orderId={}", orderId);
            return true;

        } catch (Exception e) {
            log.error("Error marking order as paid: orderId={}", orderId, e);

            walService.logOperationFailure(
                    "ORDER_PAYMENT_ERROR",
                    "orders",
                    e.getMessage()
            );

            return false;
        }
    }

    /**
     * 주문 상태 변경 (범용)
     *
     * @param orderId 주문 ID
     * @param newStatus 새 상태
     * @param reason 변경 사유
     * @return 성공 여부
     */
    public boolean updateOrderStatus(String orderId, String newStatus, String reason) {
        try {
            log.info("Updating order status: orderId={}, newStatus={}, reason={}",
                    orderId, newStatus, reason);

            // 1. 주문 조회
            Order order = getOrder(orderId);
            if (order == null) {
                log.warn("Order not found: orderId={}", orderId);
                return false;
            }

            String oldStatus = order.getStatus().name();

            // 2. WAL 로그
            String walLogId = walService.logOperationStart(
                    "ORDER_STATUS_CHANGE",
                    "orders",
                    buildOrderStatusJson(orderId, newStatus)
            );

            // 3. 상태 변경
            order.setStatus(OrderStatus.valueOf(newStatus));
            order.setUpdatedAt(LocalDateTime.now());

            // 4. 캐시 업데이트
            String cacheKey = "order:" + orderId;
            cacheService.cacheData(cacheKey, order, ORDER_CACHE_TTL_SECONDS);

            // 5. WAL 완료
            walService.logOperationComplete(
                    "ORDER_STATUS_CHANGE_COMPLETE",
                    "orders",
                    buildOrderStatusJson(orderId, oldStatus),
                    buildOrderStatusJson(orderId, newStatus)
            );
            walService.updateLogStatus(walLogId, "COMMITTED", "상태 변경 완료: " + reason);

            log.info("Order status updated: orderId={}, {} -> {}", orderId, oldStatus, newStatus);
            return true;

        } catch (Exception e) {
            log.error("Error updating order status: orderId={}, newStatus={}", orderId, newStatus, e);

            walService.logOperationFailure(
                    "ORDER_STATUS_CHANGE_ERROR",
                    "orders",
                    e.getMessage()
            );

            return false;
        }
    }

    /**
     * 주문 취소
     *
     * @param orderId 주문 ID
     * @param customerId 고객 ID (권한 확인용)
     * @param reason 취소 사유
     * @return 성공 여부
     */
    public boolean cancelOrder(String orderId, String customerId, String reason) {
        try {
            log.info("Cancelling order: orderId={}, customerId={}, reason={}",
                    orderId, customerId, reason);

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

            // 4. 상태 변경 (취소)
            return updateOrderStatus(orderId, "CANCELLED", reason);

        } catch (Exception e) {
            log.error("Error cancelling order: orderId={}", orderId, e);
            return false;
        }
    }

    /**
     * 주문 조회
     *
     * @param orderId 주문 ID
     * @return 주문 도메인 객체 (없으면 null)
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
                    return null;
                }
            }

            log.debug("Order not found: orderId={}", orderId);
            return null;

        } catch (Exception e) {
            log.error("Error getting order: orderId={}", orderId, e);
            return null;
        }
    }

    /**
     * 주문 상태 조회 (Response DTO 변환)
     *
     * @param orderId 주문 ID
     * @return 주문 응답 DTO
     */
    public OrderResponse getOrderStatus(String orderId) {
        try {
            Order order = getOrder(orderId);

            if (order == null) {
                return OrderResponse.notFound(orderId);
            }

            return OrderResponse.builder()
                    .orderId(order.getOrderId())
                    .customerId(order.getCustomerId())
                    .items(Collections.emptyList())
                    .totalAmount(order.getAmount().getAmount())
                    .currency(order.getCurrency())
                    .status(order.getStatus().name())
                    .message("주문 조회 완료")
                    .createdAt(order.getCreatedAt())
                    .updatedAt(order.getUpdatedAt())
                    .build();

        } catch (Exception e) {
            log.error("Error getting order status: orderId={}", orderId, e);
            return OrderResponse.error(orderId, "주문 상태 조회 실패: " + e.getMessage());
        }
    }

    /**
     * 고객의 주문 목록 조회
     *
     * @param customerId 고객 ID
     * @return 주문 응답 DTO 목록
     */
    public List<OrderResponse> getCustomerOrders(String customerId) {
        // TODO: DB에서 고객의 주문 목록 조회
        // 현재는 캐시에서 개별 조회만 가능
        log.debug("Getting customer orders: customerId={}", customerId);
        return Collections.emptyList();
    }

    // ========================================
    // 내부 헬퍼 메서드
    // ========================================

    /**
     * 주문 JSON 생성 (전체 정보)
     */
    private String buildOrderJson(String orderId, String customerId, String productId,
                                  Integer quantity, BigDecimal amount, String currency,
                                  String reservationId, String status) {
        return String.format(
                "{\"orderId\":\"%s\",\"customerId\":\"%s\",\"productId\":\"%s\",\"quantity\":%d,\"amount\":%s,\"currency\":\"%s\",\"reservationId\":\"%s\",\"status\":\"%s\",\"createdAt\":\"%s\"}",
                orderId, customerId, productId, quantity, amount, currency, reservationId, status, LocalDateTime.now()
        );
    }

    /**
     * 주문 상태 JSON 생성 (간단)
     */
    private String buildOrderStatusJson(String orderId, String status) {
        return String.format(
                "{\"orderId\":\"%s\",\"status\":\"%s\",\"updatedAt\":\"%s\"}",
                orderId, status, LocalDateTime.now()
        );
    }
}
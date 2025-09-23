/**
 * ========================================
 * 3. OrderService (주문 생성 및 관리 전담)
 * ========================================
 */
package com.example.payment.application.service;

import com.example.payment.domain.entity.WalLogEntry;
import com.example.payment.domain.exception.OrderException;
import com.example.payment.domain.model.common.Money;
import com.example.payment.domain.model.order.Order;
import com.example.payment.domain.model.order.OrderStatus;
import com.example.payment.infrastructure.persistence.redis.repository.CacheService;
import com.example.payment.infrastructure.persistence.wal.WalLogRepository;
import com.example.payment.infrastructure.util.IdGenerator;
import com.example.payment.presentation.dto.response.OrderResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderService {

    private final WalLogRepository walLogRepository;
    private final CacheService cacheService;

    /**
     * 주문 생성 - WAL 2단계 방식
     */
    public Order createOrder(String customerId, String productId, Integer quantity,
                             BigDecimal amount, String currency, String reservationId) {

        log.info("Creating order with WAL: customerId={}, productId={}, reservationId={}",
                customerId, productId, reservationId);

        String transactionId = IdGenerator.generateTransactionId();
        String orderId = IdGenerator.generateOrderId();

        try {
            // ========================================
            // Phase 1: WAL 기록 + 캐시 저장
            // ========================================

            // 1-1. WAL 시작 로그 기록
            WalLogEntry startLog = createOrderStartLog(transactionId, orderId, customerId, productId,
                    quantity, amount, currency, reservationId);
            walLogRepository.writeLog(startLog);

            // 1-2. 도메인 객체 생성
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

            // 1-3. Write-Through: 즉시 캐시 저장
            String cacheKey = "order:" + orderId;
            cacheService.cacheData(cacheKey, order, 86400); // 24시간

            // 1-4. WAL 완료 로그 기록 (Phase 1 성공)
            WalLogEntry completeLog = createOrderCompleteLog(transactionId, orderId, order);
            walLogRepository.writeLog(completeLog);
            walLogRepository.updateLogStatus(startLog.getLogId(), "COMMITTED", "Phase 1 완료: 주문 생성 성공");

            log.info("Order created successfully with WAL: orderId={}, transactionId={}",
                    orderId, transactionId);

            return order;

        } catch (Exception e) {
            log.error("Error creating order with WAL: customerId={}, reservationId={}, transactionId={}",
                    customerId, reservationId, transactionId, e);

            // WAL 에러 로그 기록
            try {
                walLogRepository.writeLog(createOrderErrorLog(transactionId, orderId, e.getMessage()));
            } catch (Exception walError) {
                log.error("Failed to write WAL error log", walError);
            }

            throw new OrderException("주문 생성 중 오류 발생", e);
        }
    }

    /**
     * 주문 결제 완료 처리 - Phase 2 (결제 완료 후 호출)
     */
    public boolean updateOrderToPaidWithWal(String orderId, String paymentId) {

        log.info("Updating order to PAID with WAL: orderId={}, paymentId={}", orderId, paymentId);

        String transactionId = IdGenerator.generateTransactionId();

        try {
            // ========================================
            // Phase 2: WAL 기록 + 주문 상태 업데이트
            // ========================================

            // 2-1. 현재 주문 조회
            String cacheKey = "order:" + orderId;
            Object cachedData = cacheService.getCachedData(cacheKey);

            if (cachedData == null) {
                log.warn("Order not found for payment update: orderId={}", orderId);
                return false;
            }

            Order order = (Order) cachedData;

            // 2-2. WAL Phase 2 시작 로그
            WalLogEntry phase2StartLog = createOrderPaymentStartLog(transactionId, orderId, paymentId, order);
            walLogRepository.writeLog(phase2StartLog);

            // 2-3. 주문 상태 업데이트 (PAID로 변경)
            order.markAsPaid(paymentId);

            // 2-4. 캐시 업데이트
            cacheService.cacheData(cacheKey, order, 86400);

            // 2-5. WAL Phase 2 완료 로그
            WalLogEntry phase2CompleteLog = createOrderPaymentCompleteLog(transactionId, orderId, paymentId);
            walLogRepository.writeLog(phase2CompleteLog);
            walLogRepository.updateLogStatus(phase2StartLog.getLogId(), "COMMITTED", "Phase 2 완료: 주문 결제 완료");

            log.info("Order payment status updated successfully with WAL: orderId={}, transactionId={}",
                    orderId, transactionId);

            return true;

        } catch (Exception e) {
            log.error("Error updating order payment status with WAL: orderId={}, transactionId={}",
                    orderId, transactionId, e);

            // WAL 에러 로그 기록
            try {
                walLogRepository.writeLog(createOrderPaymentErrorLog(transactionId, orderId, e.getMessage()));
            } catch (Exception walError) {
                log.error("Failed to write WAL error log", walError);
            }

            return false;
        }
    }

    /**
     * 주문 상태 업데이트 (범용)
     */
    public boolean updateOrderStatus(String orderId, String newStatus, String message) {
        try {
            log.info("Updating order status: orderId={}, newStatus={}", orderId, newStatus);

            // 캐시에서 주문 조회
            String cacheKey = "order:" + orderId;
            Object cachedData = cacheService.getCachedData(cacheKey);

            if (cachedData != null) {
                Order order = (Order) cachedData;

                // 상태 변경
                order.setStatus(OrderStatus.valueOf(newStatus));
                order.setUpdatedAt(LocalDateTime.now());

                // 캐시 업데이트
                cacheService.cacheData(cacheKey, order, 86400);

                log.info("Order status updated: orderId={}, newStatus={}", orderId, newStatus);
                return true;
            }

            log.warn("Order not found for status update: orderId={}", orderId);
            return false;

        } catch (Exception e) {
            log.error("Error updating order status: orderId={}, newStatus={}", orderId, newStatus, e);
            return false;
        }
    }

    /**
     * 주문 취소
     */
    public boolean cancelOrder(String orderId, String customerId, String reason) {
        try {
            log.info("Cancelling order: orderId={}, customerId={}, reason={}", orderId, customerId, reason);

            // 캐시에서 주문 조회
            String cacheKey = "order:" + orderId;
            Object cachedData = cacheService.getCachedData(cacheKey);

            if (cachedData != null) {
                Order order = (Order) cachedData;

                // 취소 가능 여부 확인
                if (!order.canBeCancelled()) {
                    log.warn("Order cannot be cancelled: orderId={}, status={}", orderId, order.getStatus());
                    return false;
                }

                // 상태 변경
                order.setStatus(OrderStatus.CANCELLED);
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

    /**
     * 주문 상태 조회 (Response 객체 반환)
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
                    .items(Collections.emptyList()) // 필요시 추가
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
     */
    public List<OrderResponse> getCustomerOrders(String customerId) {
        try {
            // TODO: 실제 구현에서는 DB에서 고객의 주문 목록을 조회
            log.debug("Getting customer orders: customerId={}", customerId);

            // 현재는 빈 목록 반환 (캐시에서는 개별 조회만 가능)
            return Collections.emptyList();

        } catch (Exception e) {
            log.error("Error getting customer orders: customerId={}", customerId, e);
            return Collections.emptyList();
        }
    }

    // ========================================
    // WAL 로그 생성 헬퍼 메서드들
    // ========================================

    private WalLogEntry createOrderStartLog(String transactionId, String orderId, String customerId,
                                            String productId, Integer quantity, BigDecimal amount,
                                            String currency, String reservationId) {
        return WalLogEntry.builder()
                .logId(IdGenerator.generateEventId())
                .transactionId(transactionId)
                .operation("ORDER_CREATE_START")
                .tableName("orders")
                .beforeData(null)
                .afterData(String.format(
                        "{\"orderId\":\"%s\",\"customerId\":\"%s\",\"productId\":\"%s\",\"quantity\":%d,\"amount\":%s,\"currency\":\"%s\",\"reservationId\":\"%s\",\"status\":\"CREATED\"}",
                        orderId, customerId, productId, quantity, amount, currency, reservationId))
                .status("PENDING")
                .message("주문 생성 시작")
                .createdAt(LocalDateTime.now())
                .build();
    }

    private WalLogEntry createOrderCompleteLog(String transactionId, String orderId, Order order) {
        return WalLogEntry.builder()
                .logId(IdGenerator.generateEventId())
                .transactionId(transactionId)
                .operation("ORDER_CREATE_COMPLETE")
                .tableName("orders")
                .beforeData(null)
                .afterData(String.format(
                        "{\"orderId\":\"%s\",\"status\":\"CREATED\",\"createdAt\":\"%s\"}",
                        orderId, order.getCreatedAt()))
                .status("COMMITTED")
                .message("주문 생성 완료")
                .createdAt(LocalDateTime.now())
                .build();
    }

    private WalLogEntry createOrderErrorLog(String transactionId, String orderId, String errorMessage) {
        return WalLogEntry.builder()
                .logId(IdGenerator.generateEventId())
                .transactionId(transactionId)
                .operation("ORDER_CREATE_ERROR")
                .tableName("orders")
                .beforeData(null)
                .afterData(String.format("{\"orderId\":\"%s\",\"error\":\"%s\"}", orderId, errorMessage))
                .status("FAILED")
                .message("주문 생성 실패: " + errorMessage)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private WalLogEntry createOrderPaymentStartLog(String transactionId, String orderId, String paymentId, Order order) {
        return WalLogEntry.builder()
                .logId(IdGenerator.generateEventId())
                .transactionId(transactionId)
                .operation("ORDER_PAYMENT_START")
                .tableName("orders")
                .beforeData(String.format("{\"orderId\":\"%s\",\"status\":\"%s\"}", orderId, order.getStatus()))
                .afterData(String.format(
                        "{\"orderId\":\"%s\",\"paymentId\":\"%s\",\"status\":\"PAID\"}",
                        orderId, paymentId))
                .status("PENDING")
                .message("주문 결제 완료 처리 시작")
                .createdAt(LocalDateTime.now())
                .build();
    }

    private WalLogEntry createOrderPaymentCompleteLog(String transactionId, String orderId, String paymentId) {
        return WalLogEntry.builder()
                .logId(IdGenerator.generateEventId())
                .transactionId(transactionId)
                .operation("ORDER_PAYMENT_COMPLETE")
                .tableName("orders")
                .beforeData(String.format("{\"orderId\":\"%s\",\"status\":\"CREATED\"}", orderId))
                .afterData(String.format(
                        "{\"orderId\":\"%s\",\"paymentId\":\"%s\",\"status\":\"PAID\"}",
                        orderId, paymentId))
                .status("COMMITTED")
                .message("주문 결제 완료 처리 완료")
                .createdAt(LocalDateTime.now())
                .build();
    }

    private WalLogEntry createOrderPaymentErrorLog(String transactionId, String orderId, String errorMessage) {
        return WalLogEntry.builder()
                .logId(IdGenerator.generateEventId())
                .transactionId(transactionId)
                .operation("ORDER_PAYMENT_ERROR")
                .tableName("orders")
                .beforeData(null)
                .afterData(String.format("{\"orderId\":\"%s\",\"error\":\"%s\"}", orderId, errorMessage))
                .status("FAILED")
                .message("주문 결제 완료 처리 실패: " + errorMessage)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
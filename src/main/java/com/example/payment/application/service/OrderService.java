/**
 * ========================================
 * 3. OrderService (주문 생성 및 관리 전담)
 * ========================================
 */
package com.example.payment.application.service;

import com.example.payment.domain.model.CompletedOrder;
import com.example.payment.infrastructure.buffer.WriteBufferService;
import com.example.payment.infrastructure.buffer.OrderWriteCommand;
import com.example.payment.domain.model.ReservationState;
import com.example.payment.domain.repository.ReservationRepository;
import com.example.payment.infrastructure.persistance.redis.repository.CacheService;
import com.example.payment.infrastructure.util.IdGenerator;
import com.example.payment.presentation.dto.response.OrderResponse;
import com.example.payment.application.dto.CreateOrderRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderService {

    private final WriteBufferService writeBufferService;
    private final CacheService cacheService;
    private final ReservationRepository reservationRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 주문 생성 (결제 성공 후 호출됨)
     * - PaymentProcessingService에서 결제 성공 시 호출
     */
    public String createOrder(CreateOrderRequest request) {
        log.info("Creating order: customerId={}, productId={}, reservationId={}",
                request.getCustomerId(), request.getProductId(), request.getReservationId());

        try {
            // 1. 주문 ID 생성
            String orderId = IdGenerator.generateOrderId();

            // 2. MySQL 쓰기를 버퍼에 비동기 큐잉
            OrderWriteCommand writeCommand = new OrderWriteCommand(
                    orderId, request.getCustomerId(), request.getProductId(),
                    request.getQuantity(), request.getAmount(), request.getCurrency(),
                    request.getPaymentId(), request.getReservationId()
            );

            writeBufferService.enqueue(writeCommand);

            // 3. 주문 정보 생성 및 캐시 저장
            CompletedOrder order = CompletedOrder.builder()
                    .orderId(orderId)
                    .customerId(request.getCustomerId())
                    .productId(request.getProductId())
                    .quantity(request.getQuantity())
                    .amount(request.getAmount())
                    .currency(request.getCurrency())
                    .paymentId(request.getPaymentId())
                    .reservationId(request.getReservationId())
                    .status("CONFIRMED")
                    .createdAt(LocalDateTime.now())
                    .build();

            // 4. 주문 정보 캐시에 저장 (빠른 조회를 위해)
            String orderCacheKey = "order:" + orderId;
            cacheService.cacheData(orderCacheKey, order, 86400); // 24시간

            // 5. 고객별 주문 목록에도 추가 (선택적)
            String customerOrdersKey = "customer-orders:" + request.getCustomerId();
            // 구현 생략: 고객별 주문 목록 관리

            log.info("Order created successfully: orderId={}, customerId={}", orderId, request.getCustomerId());

            return orderId;

        } catch (Exception e) {
            log.error("Error creating order: customerId={}, reservationId={}",
                    request.getCustomerId(), request.getReservationId(), e);
            throw new RuntimeException("주문 생성 중 오류가 발생했습니다: " + e.getMessage(), e);
        }
    }

    /**
     * 주문 상태 조회
     */
    public OrderResponse getOrderStatus(String orderId) {
        log.debug("Getting order status: orderId={}", orderId);

        try {
            // 주문 캐시에서 조회
            String cacheKey = "order:" + orderId;
            Object cachedData = cacheService.getCachedData(cacheKey);

            if (cachedData != null) {
                CompletedOrder order;
                if (cachedData instanceof CompletedOrder) {
                    order = (CompletedOrder) cachedData;
                } else {
                    order = objectMapper.convertValue(cachedData, CompletedOrder.class);
                }

                return OrderResponse.builder()
                        .orderId(order.getOrderId())
                        .customerId(order.getCustomerId())
                        .totalAmount(order.getAmount())
                        .currency(order.getCurrency())
                        .status(order.getStatus())
                        .message("주문이 확정되었습니다.")
                        .createdAt(order.getCreatedAt())
                        .updatedAt(LocalDateTime.now())
                        .build();
            }

            // 주문을 찾을 수 없음
            log.warn("Order not found: orderId={}", orderId);
            return null;

        } catch (Exception e) {
            log.error("Error getting order status: orderId={}", orderId, e);
            return OrderResponse.builder()
                    .orderId(orderId)
                    .status("ERROR")
                    .message("주문 조회 중 오류가 발생했습니다.")
                    .updatedAt(LocalDateTime.now())
                    .build();
        }
    }

    /**
     * 주문 취소 (특정 조건에서만 가능)
     */
    @Transactional
    public boolean cancelOrder(String orderId, String customerId, String reason) {
        log.info("Attempting to cancel order: orderId={}, customerId={}", orderId, customerId);

        try {
            // 1. 주문 정보 조회
            String cacheKey = "order:" + orderId;
            Object cachedData = cacheService.getCachedData(cacheKey);

            if (cachedData == null) {
                log.warn("Order not found for cancellation: orderId={}", orderId);
                return false;
            }

            CompletedOrder order;
            if (cachedData instanceof CompletedOrder) {
                order = (CompletedOrder) cachedData;
            } else {
                order = objectMapper.convertValue(cachedData, CompletedOrder.class);
            }

            // 2. 취소 가능 여부 확인
            if (!customerId.equals(order.getCustomerId())) {
                log.warn("Unauthorized order cancellation attempt: orderId={}, customerId={}", orderId, customerId);
                return false;
            }

            if (!"CONFIRMED".equals(order.getStatus())) {
                log.warn("Cannot cancel order in status: orderId={}, status={}", orderId, order.getStatus());
                return false;
            }

            // 3. 취소 처리 (비즈니스 규칙에 따라 제한적으로 허용)
            // 예: 티켓의 경우 공연 24시간 전까지만 취소 가능 등

            // 주문 상태 변경
            order.setStatus("CANCELLED");
            cacheService.cacheData(cacheKey, order, 86400);

            // 4. 관련 예약도 취소 상태로 변경
            if (order.getReservationId() != null) {
                Optional<com.example.payment.domain.model.inventory.Reservation> reservationOpt =
                        reservationRepository.findById(order.getReservationId());

                if (reservationOpt.isPresent()) {
                    com.example.payment.domain.model.inventory.Reservation reservation = reservationOpt.get();
                    reservation.setStatus(com.example.payment.domain.model.inventory.Reservation.ReservationStatus.CANCELLED);
                    reservationRepository.save(reservation);
                }
            }

            // 5. 취소 이벤트 발행 (환불, 재고 복원 등을 위해)
            publishOrderCancelledEvent(orderId, reason);

            log.info("Order cancelled successfully: orderId={}", orderId);
            return true;

        } catch (Exception e) {
            log.error("Error cancelling order: orderId={}, customerId={}", orderId, customerId, e);
            return false;
        }
    }

    /**
     * 고객의 주문 목록 조회
     */
    public java.util.List<OrderResponse> getCustomerOrders(String customerId) {
        log.debug("Getting customer orders: customerId={}", customerId);

        try {
            // TODO: 실제 구현에서는 페이징 처리 필요
            String customerOrdersKey = "customer-orders:" + customerId;
            Object cachedData = cacheService.getCachedData(customerOrdersKey);

            if (cachedData != null) {
                // 캐시된 주문 목록 반환
                // 구현 생략
            }

            // DB에서 조회하는 로직도 필요
            // 구현 생략

            return java.util.Collections.emptyList();

        } catch (Exception e) {
            log.error("Error getting customer orders: customerId={}", customerId, e);
            return java.util.Collections.emptyList();
        }
    }

    /**
     * 주문 취소 이벤트 발행
     */
    private void publishOrderCancelledEvent(String orderId, String reason) {
        try {
            String eventJson = objectMapper.writeValueAsString(java.util.Map.of(
                    "eventType", "ORDER_CANCELLED",
                    "orderId", orderId,
                    "reason", reason,
                    "timestamp", System.currentTimeMillis()
            ));

            kafkaTemplate.send("order-events", orderId, eventJson);
            log.info("Order cancelled event published: orderId={}", orderId);

        } catch (Exception e) {
            log.error("Error publishing order cancelled event: orderId={}", orderId, e);
        }
    }

    /**
     * 주문 상태 업데이트 (배송, 완료 등)
     */
    @Transactional
    public boolean updateOrderStatus(String orderId, String newStatus, String message) {
        log.info("Updating order status: orderId={}, newStatus={}", orderId, newStatus);

        try {
            String cacheKey = "order:" + orderId;
            Object cachedData = cacheService.getCachedData(cacheKey);

            if (cachedData == null) {
                log.warn("Order not found for status update: orderId={}", orderId);
                return false;
            }

            CompletedOrder order;
            if (cachedData instanceof CompletedOrder) {
                order = (CompletedOrder) cachedData;
            } else {
                order = objectMapper.convertValue(cachedData, CompletedOrder.class);
            }

            // 상태 업데이트
            order.setStatus(newStatus);
            cacheService.cacheData(cacheKey, order, 86400);

            // 상태 변경 이벤트 발행
            publishOrderStatusChangedEvent(orderId, newStatus, message);

            log.info("Order status updated: orderId={}, newStatus={}", orderId, newStatus);
            return true;

        } catch (Exception e) {
            log.error("Error updating order status: orderId={}, newStatus={}", orderId, newStatus, e);
            return false;
        }
    }

    /**
     * 주문 상태 변경 이벤트 발행
     */
    private void publishOrderStatusChangedEvent(String orderId, String newStatus, String message) {
        try {
            String eventJson = objectMapper.writeValueAsString(java.util.Map.of(
                    "eventType", "ORDER_STATUS_CHANGED",
                    "orderId", orderId,
                    "newStatus", newStatus,
                    "message", message != null ? message : "",
                    "timestamp", System.currentTimeMillis()
            ));

            kafkaTemplate.send("order-events", orderId, eventJson);
            log.debug("Order status changed event published: orderId={}, status={}", orderId, newStatus);

        } catch (Exception e) {
            log.error("Error publishing order status changed event: orderId={}", orderId, e);
        }
    }
}
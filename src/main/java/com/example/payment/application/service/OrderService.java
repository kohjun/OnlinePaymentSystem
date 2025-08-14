/**
 * ========================================
 * 고성능 OLTP용 주문 서비스 (패턴 B 전용)
 * ========================================
 * 플로우: 재고 즉시 선점 → 결제 처리 → 주문 생성
 */
package com.example.payment.application.service;

import java.time.LocalDateTime;
import java.util.UUID;

import com.example.payment.infrastructure.persistance.redis.repository.CacheService;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.payment.presentation.dto.request.OrderRequest;
import com.example.payment.presentation.dto.response.OrderResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderService {

    private final CacheService cacheService;
    private final InventoryManagementService inventoryService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    // 고성능 설정
    private static final int RESERVATION_TTL = 300; // 5분 (한정 상품)
    private static final String ORDER_EVENTS_TOPIC = "order-events";

    /**
     * 1단계: 재고 즉시 선점 (패턴 B의 핵심)
     * 고트래픽 상황에서 최고 성능을 위한 설계
     */
    @Transactional(timeout = 5) // 매우 빠른 트랜잭션
    public OrderResponse reserveInventory(OrderRequest request) {
        log.info("Attempting inventory reservation: productId={}, customerId={}, quantity={}",
                request.getProductId(), request.getCustomerId(), request.getQuantity());

        try {
            // 1. 실시간 재고 확인
            boolean isAvailable = inventoryService.checkRealTimeAvailability(
                    request.getProductId(),
                    request.getQuantity()
            );

            if (!isAvailable) {
                log.warn("Product out of stock: productId={}", request.getProductId());
                return OrderResponse.outOfStock(request.getProductId());
            }

            // 2. 즉시 재고 선점 (원자적 처리)
            String reservationId = generateReservationId();
            String paymentId = UUID.randomUUID().toString();
            LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(RESERVATION_TTL);

            InventoryManagementService.InventoryReservationResult result =
                    inventoryService.reserveInventoryImmediately(
                            request.getProductId(),
                            reservationId,
                            request.getQuantity(),
                            request.getCustomerId(),
                            RESERVATION_TTL
                    );

            if (!result.isSuccess()) {
                log.warn("Inventory reservation failed: error={}", result.getErrorMessage());
                return OrderResponse.failed(request.getProductId(),
                        "RESERVATION_FAILED", result.getErrorMessage());
            }

            // 3. 예약 상태 캐시 저장
            ReservationState reservationState = ReservationState.builder()
                    .reservationId(reservationId)
                    .paymentId(paymentId)
                    .productId(request.getProductId())
                    .customerId(request.getCustomerId())
                    .quantity(request.getQuantity())
                    .amount(request.getAmount())
                    .currency(request.getCurrency())
                    .status("RESERVED")
                    .createdAt(LocalDateTime.now())
                    .expiresAt(expiresAt)
                    .build();

            String cacheKey = "reservation:" + reservationId;
            cacheService.cacheData(cacheKey, reservationState, RESERVATION_TTL);

            // 4. 성공 응답 생성
            OrderResponse response = OrderResponse.reserved(
                    reservationId,
                    paymentId,
                    request.getProductId(),
                    request.getQuantity(),
                    request.getAmount(),
                    request.getCurrency(),
                    expiresAt
            );

            log.info("Inventory reserved successfully: reservationId={}, expiresAt={}",
                    reservationId, expiresAt);

            return response;

        } catch (Exception e) {
            log.error("Error in inventory reservation: productId={}, error={}",
                    request.getProductId(), e.getMessage(), e);
            return OrderResponse.failed(request.getProductId(),
                    "SYSTEM_ERROR", "시스템 오류로 상품을 확보할 수 없습니다");
        }
    }

    /**
     * 예약 상태 조회 (고성능 캐시 기반)
     */
    public ReservationStatusResponse getReservationStatus(String reservationId) {
        try {
            String cacheKey = "reservation:" + reservationId;
            Object reservationData = cacheService.getCachedData(cacheKey);

            if (reservationData == null) {
                return null; // 404 처리
            }

            ReservationState reservation;
            if (reservationData instanceof ReservationState) {
                reservation = (ReservationState) reservationData;
            } else {
                reservation = objectMapper.convertValue(reservationData, ReservationState.class);
            }

            // 만료 확인
            boolean isExpired = LocalDateTime.now().isAfter(reservation.getExpiresAt());
            if (isExpired) {
                // 만료된 예약 정리
                cleanupExpiredReservation(reservationId, reservation);
            }

            return ReservationStatusResponse.builder()
                    .reservationId(reservationId)
                    .productId(reservation.getProductId())
                    .quantity(reservation.getQuantity())
                    .status(isExpired ? "EXPIRED" : reservation.getStatus())
                    .expiresAt(reservation.getExpiresAt())
                    .remainingSeconds(isExpired ? 0 :
                            java.time.Duration.between(LocalDateTime.now(), reservation.getExpiresAt()).getSeconds())
                    .build();

        } catch (Exception e) {
            log.error("Error checking reservation status: reservationId={}", reservationId, e);
            return null;
        }
    }

    /**
     * 결제 완료 후 주문 생성 (패턴 B의 마지막 단계)
     */
    @Transactional(timeout = 10)
    public String createConfirmedOrder(CreateOrderRequest request) {
        try {
            log.info("Creating confirmed order after payment: paymentId={}, reservationId={}",
                    request.getPaymentId(), request.getReservationId());

            // 주문 ID 생성 (결제 완료 후에 비로소!)
            String orderId = "ORD-" + System.currentTimeMillis() + "-" +
                    UUID.randomUUID().toString().substring(0, 8);

            // 주문 정보를 캐시에 저장
            CompletedOrder completedOrder = CompletedOrder.builder()
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

            String orderCacheKey = "order:" + orderId;
            cacheService.cacheData(orderCacheKey, completedOrder, 86400); // 24시간

            // 주문 생성 이벤트 발행 (트랜잭션 완료 후)
            publishOrderCreatedEvent(completedOrder);

            log.info("Order created successfully: orderId={}", orderId);
            return orderId;

        } catch (Exception e) {
            log.error("Error creating confirmed order: paymentId={}, error={}",
                    request.getPaymentId(), e.getMessage(), e);
            throw new RuntimeException("주문 생성 실패: " + e.getMessage(), e);
        }
    }

    // ========== 헬퍼 메서드들 ==========

    private void cleanupExpiredReservation(String reservationId, ReservationState reservation) {
        try {
            // 재고 해제
            inventoryService.cancelReservation(reservationId);

            // 캐시 정리
            String cacheKey = "reservation:" + reservationId;
            cacheService.deleteCache(cacheKey);

            log.info("Expired reservation cleaned up: reservationId={}", reservationId);

        } catch (Exception e) {
            log.error("Error cleaning up expired reservation: reservationId={}", reservationId, e);
        }
    }

    private void publishOrderCreatedEvent(CompletedOrder order) {
        try {
            String eventJson = objectMapper.writeValueAsString(order);
            kafkaTemplate.send(ORDER_EVENTS_TOPIC, order.getOrderId(), eventJson);
            log.debug("Order created event published: orderId={}", order.getOrderId());

        } catch (Exception e) {
            log.error("Error publishing order created event: orderId={}", order.getOrderId(), e);
        }
    }

    private String generateReservationId() {
        return "RSV-" + System.currentTimeMillis() + "-" +
                UUID.randomUUID().toString().substring(0, 8);
    }
}
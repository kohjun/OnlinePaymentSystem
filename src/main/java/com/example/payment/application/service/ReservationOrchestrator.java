package com.example.payment.application.service;

import com.example.payment.application.service.InventoryManagementService;
import com.example.payment.domain.model.inventory.InventoryConfirmation;
import com.example.payment.domain.model.order.Order;
import com.example.payment.domain.model.payment.Payment;
import com.example.payment.domain.model.reservation.InventoryReservation;
import com.example.payment.infrastructure.persistence.redis.repository.CacheService;
import com.example.payment.infrastructure.util.IdGenerator;
import com.example.payment.presentation.dto.request.CompleteReservationRequest;
import com.example.payment.presentation.dto.response.CompleteReservationResponse;
import com.example.payment.presentation.dto.response.OrderResponse;
import com.example.payment.presentation.dto.response.ReservationStatusResponse;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class ReservationOrchestrator {

    // 4개 서비스 의존성
    private final ReservationService reservationService;
    private final OrderService orderService;
    private final PaymentProcessingService paymentProcessingService;
    private final InventoryManagementService inventoryManagementService;

    // 이벤트 및 캐싱
    //private final EventPublisher eventPublisher;
    private final CacheService cacheService;

    /**
     * 통합 예약 플로우
     */
    public CompleteReservationResponse processCompleteReservation(CompleteReservationRequest request) {

        log.info("Starting complete reservation flow: customerId={}, productId={}, quantity={}",
                request.getCustomerId(), request.getProductId(), request.getQuantity());

        String correlationId = IdGenerator.generateCorrelationId();

        try {
            // ========================================
            // 1단계: 재고 선점
            // ========================================
            InventoryReservation reservation = reservationService.reserveInventory(
                    request.getProductId(),
                    request.getCustomerId(),
                    request.getQuantity(),
                    request.getClientId()
            );

            if (reservation == null) {
                return CompleteReservationResponse.failed("재고 선점 실패: 재고가 부족합니다");
            }

            // ========================================
            // 2단계: 주문 생성
            // ========================================
            Order order;
            try {
                order = orderService.createOrder(
                        request.getCustomerId(),
                        request.getProductId(),
                        request.getQuantity(),
                        request.getAmount(),
                        request.getCurrency(),
                        reservation.getReservationId()
                );

            } catch (Exception e) {
                // 보상: 재고 예약 취소
                compensateReservation(reservation.getReservationId(), request.getCustomerId());
                return CompleteReservationResponse.failed("주문 생성 실패: " + e.getMessage());
            }

            // ========================================
            // 3단계: 결제 처리
            // ========================================
            Payment payment;
            try {
                String paymentId = IdGenerator.generatePaymentId();

                payment = paymentProcessingService.processPayment(
                        paymentId,
                        order.getOrderId(),
                        reservation.getReservationId(),
                        request.getCustomerId(),
                        request.getAmount(),
                        request.getCurrency(),
                        request.getPaymentMethod()
                );

                if (!"COMPLETED".equals(payment.getStatus())) {
                    // 보상: 주문 취소 + 재고 예약 취소
                    compensateOrder(order.getOrderId(), request.getCustomerId());
                    compensateReservation(reservation.getReservationId(), request.getCustomerId());
                    return CompleteReservationResponse.failed("결제 실패: " + payment.getStatus());
                }

            } catch (Exception e) {
                // 보상: 주문 취소 + 재고 예약 취소
                compensateOrder(order.getOrderId(), request.getCustomerId());
                compensateReservation(reservation.getReservationId(), request.getCustomerId());
                return CompleteReservationResponse.failed("결제 처리 실패: " + e.getMessage());
            }

            // ========================================
            // 4단계: 재고 확정
            // ========================================
            InventoryConfirmation confirmation;
            try {
                confirmation = inventoryManagementService.confirmReservation(
                        reservation.getReservationId(),
                        order.getOrderId(),
                        payment.getPaymentId()
                );

                if (!confirmation.isConfirmed()) {
                    // 보상: 결제 환불 + 주문 취소 + 재고 예약 취소
                    compensatePayment(payment.getPaymentId());
                    compensateOrder(order.getOrderId(), request.getCustomerId());
                    compensateReservation(reservation.getReservationId(), request.getCustomerId());
                    return CompleteReservationResponse.failed("재고 확정 실패: " + confirmation.getReason());
                }

            } catch (Exception e) {
                // 보상: 결제 환불 + 주문 취소 + 재고 예약 취소
                compensatePayment(payment.getPaymentId());
                compensateOrder(order.getOrderId(), request.getCustomerId());
                compensateReservation(reservation.getReservationId(), request.getCustomerId());
                return CompleteReservationResponse.failed("확정 처리 실패: " + e.getMessage());
            }

            // ========================================
            // 5단계: 성공 처리
            // ========================================
            publishSuccessEvents(reservation, order, payment, confirmation, correlationId);
            cacheCompleteResult(reservation, order, payment, confirmation);

            log.info("Complete reservation succeeded: reservationId={}, orderId={}, paymentId={}",
                    reservation.getReservationId(), order.getOrderId(), payment.getPaymentId());

            // 통합 Response 생성
            return CompleteReservationResponse.success(
                    reservation.getReservationId(),
                    order.getOrderId(),
                    payment.getPaymentId(),
                    payment.getTransactionId(),
                    request.getProductId(),
                    request.getQuantity(),
                    request.getAmount(),
                    request.getCurrency(),
                    "예약이 성공적으로 완료되었습니다"
            );

        } catch (Exception e) {
            log.error("Complete reservation failed: customerId={}, productId={}",
                    request.getCustomerId(), request.getProductId(), e);

            return CompleteReservationResponse.failed("시스템 오류: " + e.getMessage());
        }
    }

    /**
     * 개별 조회 API들 (도메인 객체를 Response로 변환)
     */
    public ReservationStatusResponse getReservationStatus(String reservationId) {
        InventoryReservation reservation = reservationService.getReservation(reservationId);

        if (reservation == null) {
            return null;
        }

        return ReservationStatusResponse.builder()
                .reservationId(reservation.getReservationId())
                .productId(reservation.getProductId())
                .quantity(reservation.getQuantity())
                .status(String.valueOf(reservation.getStatus()))
                .expiresAt(reservation.getExpiresAt())
                .remainingSeconds(reservation.getRemainingSeconds())
                .message("예약 상태 조회 완료")
                .build();
    }

    public OrderResponse getOrderStatus(String orderId) {
        Order order = orderService.getOrder(orderId);

        if (order == null) {
            return OrderResponse.notFound(orderId);
        }

        return OrderResponse.builder()
                .orderId(order.getOrderId())
                .customerId(order.getCustomerId())
                .totalAmount(order.getAmount().getAmount())
                .currency(order.getCurrency())
                .status(String.valueOf(order.getStatus()))
                .message("주문 상태 조회 완료")
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .build();
    }

    // 보상 트랜잭션들
    private void compensateReservation(String reservationId, String customerId) {
        try {
            reservationService.cancelReservation(reservationId, customerId);
            log.info("Reservation compensated: reservationId={}", reservationId);
        } catch (Exception e) {
            log.error("Failed to compensate reservation: reservationId={}", reservationId, e);
        }
    }

    private void compensateOrder(String orderId, String customerId) {
        try {
            orderService.cancelOrder(orderId, customerId);
            log.info("Order compensated: orderId={}", orderId);
        } catch (Exception e) {
            log.error("Failed to compensate order: orderId={}", orderId, e);
        }
    }

    private void compensatePayment(String paymentId) {
        try {
            paymentProcessingService.refundPayment(paymentId);
            log.info("Payment compensated: paymentId={}", paymentId);
        } catch (Exception e) {
            log.error("Failed to compensate payment: paymentId={}", paymentId, e);
        }
    }

    // 이벤트 발행 및 캐싱 메서드들...
    private void publishSuccessEvents(InventoryReservation reservation, Order order,
                                      Payment payment, InventoryConfirmation confirmation, String correlationId) {
        // Kafka 이벤트 발행 로직
    }

    private void cacheCompleteResult(InventoryReservation reservation, Order order,
                                     Payment payment, InventoryConfirmation confirmation) {
        // Redis 통합 결과 캐싱 로직
    }
}

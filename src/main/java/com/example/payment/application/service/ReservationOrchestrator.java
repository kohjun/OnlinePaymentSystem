package com.example.payment.application.service;

import com.example.payment.application.event.publisher.OrderEventPublisher;
import com.example.payment.application.event.publisher.PaymentEventService;
import com.example.payment.application.event.publisher.ReservationEventPublisher;
import com.example.payment.domain.model.inventory.InventoryConfirmation;
import com.example.payment.domain.model.order.Order;
import com.example.payment.domain.model.payment.Payment;
import com.example.payment.domain.model.reservation.InventoryReservation;
import com.example.payment.infrastructure.persistence.redis.repository.CacheService;
import com.example.payment.infrastructure.util.IdGenerator;
import com.example.payment.presentation.dto.request.CompleteReservationRequest;
import com.example.payment.presentation.dto.response.CompleteReservationResponse;
import com.example.payment.presentation.dto.response.ReservationResponse;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 예약 오케스트레이터 - DDD + SOLID 원칙 준수 최종 버전
 *
 * 🎯 단일 책임: OLTP 트랜잭션 조율(Orchestration)만 담당
 *
 * 역할:
 * - 4개 도메인 서비스 조율 (Reservation, Order, Payment, Inventory)
 * - 2-Phase WAL 프로토콜 관리
 * - 보상 트랜잭션 (Saga Pattern)
 * - 비즈니스 이벤트 발행
 *
 * 하지 않는 일:
 * - 도메인 로직 X → 각 서비스에 위임
 * - WAL 로그 X → WalService
 * - 분산 락 X → DistributedLockService
 * - 캐싱 X → CacheService
 * - PG 연동 X → PaymentProcessingService
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class ReservationOrchestrator {

    // ========================================
    // 도메인 서비스들 (각각 단일 책임)
    // ========================================
    private final ReservationService reservationService;           // 재고 선점
    private final OrderService orderService;                       // 주문 관리
    private final PaymentProcessingService paymentProcessingService; // 결제 처리
    private final InventoryManagementService inventoryManagementService; // 재고 확정

    // ========================================
    // 인프라스트럭처 서비스들
    // ========================================
    private final CacheService cacheService;

    // ========================================
    // 이벤트 퍼블리셔들
    // ========================================
    private final ReservationEventPublisher reservationEventPublisher;
    private final OrderEventPublisher orderEventPublisher;
    private final PaymentEventService paymentEventService;

    /**
     * 통합 예약 플로우 - 2-Phase WAL + Saga Pattern
     *
     * Phase 1: 재고 선점 + 주문 생성 (즉시 처리, 분산 락 보장)
     * PG 연동: 외부 결제 처리
     * Phase 2: 재고 확정 + 주문 업데이트 (결제 완료 후)
     *
     * @param request 통합 예약 요청
     * @return 통합 예약 응답
     */
    public CompleteReservationResponse processCompleteReservation(CompleteReservationRequest request) {

        log.info("🚀 Starting complete reservation flow: customerId={}, productId={}, quantity={}",
                request.getCustomerId(), request.getProductId(), request.getQuantity());

        String correlationId = request.getCorrelationId() != null ?
                request.getCorrelationId() : IdGenerator.generateCorrelationId();

        try {
            // ========================================
            // Phase 1: 재고 선점 + 주문 생성
            // ========================================

            // 1-1. 재고 선점 (분산 락 내부에서 WAL 처리)
            log.debug("[Phase 1] Step 1: Reserve inventory");
            InventoryReservation reservation = reservationService.reserveInventory(
                    request.getProductId(),
                    request.getCustomerId(),
                    request.getQuantity(),
                    request.getClientId()
            );

            if (reservation == null) {
                log.warn("❌ Reservation failed: insufficient inventory");
                reservationEventPublisher.publishReservationCancelled(
                        "TEMP-" + IdGenerator.generateReservationId(),
                        "재고 부족"
                );
                return CompleteReservationResponse.failed("재고 선점 실패: 재고가 부족합니다");
            }

            log.info("✅ Reservation succeeded: reservationId={}", reservation.getReservationId());
            reservationEventPublisher.publishReservationCreated(reservation);

            // 1-2. 주문 생성 (WAL 내부 처리)
            log.debug("[Phase 1] Step 2: Create order");
            Order order;
            try {
                order = orderService.createOrder(
                        request.getCustomerId(),
                        request.getProductId(),
                        request.getQuantity(),
                        request.getPaymentInfo().getAmount(),
                        request.getPaymentInfo().getCurrency(),
                        reservation.getReservationId()
                );

                log.info("✅ Order created: orderId={}", order.getOrderId());
                orderEventPublisher.publishOrderCreated(
                        order.getOrderId(),
                        order.getCustomerId(),
                        reservation.getReservationId()
                );

            } catch (Exception e) {
                log.error("❌ Order creation failed, compensating reservation", e);
                // 보상: 재고 예약 취소
                compensateReservation(reservation.getReservationId(), request.getCustomerId());
                return CompleteReservationResponse.failed("주문 생성 실패: " + e.getMessage());
            }

            // ========================================
            // 외부 결제 처리 (PG 연동)
            // ========================================
            log.debug("[PG] Processing payment");
            Payment payment;
            try {
                String paymentId = IdGenerator.generatePaymentId();

                payment = paymentProcessingService.processPayment(
                        paymentId,
                        order.getOrderId(),
                        reservation.getReservationId(),
                        request.getCustomerId(),
                        request.getPaymentInfo().getAmount(),
                        request.getPaymentInfo().getCurrency(),
                        request.getPaymentInfo().getPaymentMethod()
                );

                if (!payment.isCompleted()) {
                    log.warn("❌ Payment failed: status={}", payment.getStatus());
                    // 보상: 주문 취소 + 재고 예약 취소
                    compensateOrder(order.getOrderId(), request.getCustomerId());
                    compensateReservation(reservation.getReservationId(), request.getCustomerId());
                    return CompleteReservationResponse.failed("결제 실패: " + payment.getStatus());
                }

                log.info("✅ Payment completed: paymentId={}, transactionId={}",
                        payment.getPaymentId(), payment.getTransactionId());

            } catch (Exception e) {
                log.error("❌ Payment processing error, compensating", e);
                compensateOrder(order.getOrderId(), request.getCustomerId());
                compensateReservation(reservation.getReservationId(), request.getCustomerId());
                return CompleteReservationResponse.failed("결제 처리 실패: " + e.getMessage());
            }

            // ========================================
            // Phase 2: 확정 처리 (결제 성공 후)
            // ========================================

            try {
                // 2-1. 재고 확정 (WAL Phase 2)
                log.debug("[Phase 2] Step 1: Confirm inventory");
                InventoryConfirmation confirmation = inventoryManagementService.confirmReservation(
                        reservation.getReservationId(),
                        order.getOrderId(),
                        payment.getPaymentId()
                );

                if (!confirmation.isConfirmed()) {
                    log.error("❌ Inventory confirmation failed: {}", confirmation.getReason());
                    // 심각한 상황: 결제는 완료되었지만 재고 확정 실패
                    // 보상: 결제 환불 + 주문 취소 + 재고 예약 취소
                    compensatePayment(payment.getPaymentId());
                    compensateOrder(order.getOrderId(), request.getCustomerId());
                    compensateReservation(reservation.getReservationId(), request.getCustomerId());
                    return CompleteReservationResponse.failed("재고 확정 실패");
                }

                log.info("✅ Inventory confirmed: reservationId={}", reservation.getReservationId());
                reservationEventPublisher.publishReservationConfirmed(
                        reservation.getReservationId(),
                        order.getOrderId(),
                        payment.getPaymentId()
                );

                // 2-2. 주문 결제 완료 처리 (WAL Phase 2)
                log.debug("[Phase 2] Step 2: Update order to PAID");
                boolean orderUpdated = orderService.markOrderAsPaid(
                        order.getOrderId(),
                        payment.getPaymentId()
                );

                if (!orderUpdated) {
                    log.error("❌ Order payment update failed: orderId={}", order.getOrderId());
                    // 이미 재고는 확정되었으므로 경고만 발행
                } else {
                    log.info("✅ Order marked as PAID: orderId={}", order.getOrderId());
                    orderEventPublisher.publishOrderStatusChanged(
                            order.getOrderId(),
                            "CREATED",
                            "PAID"
                    );
                }

            } catch (Exception e) {
                log.error("❌ Phase 2 failed", e);
                return CompleteReservationResponse.failed("확정 처리 실패: " + e.getMessage());
            }

            // ========================================
            // 성공 처리
            // ========================================

            publishSuccessEvents(reservation, order, payment, correlationId);
            cacheCompleteResult(reservation, order, payment);

            log.info("🎉 Complete reservation succeeded: reservationId={}, orderId={}, paymentId={}",
                    reservation.getReservationId(), order.getOrderId(), payment.getPaymentId());

            return CompleteReservationResponse.success(
                    reservation.getReservationId(),
                    order.getOrderId(),
                    payment.getPaymentId(),
                    payment.getTransactionId(),
                    request.getProductId(),
                    request.getQuantity(),
                    request.getPaymentInfo().getAmount(),
                    request.getPaymentInfo().getCurrency()
            );

        } catch (Exception e) {
            log.error("💥 Complete reservation failed: customerId={}, productId={}",
                    request.getCustomerId(), request.getProductId(), e);

            return CompleteReservationResponse.failed("시스템 오류: " + e.getMessage());
        }
    }

    // ========================================
    // 단순 예약 API (Phase 1만)
    // ========================================

    /**
     * 재고 선점만 처리
     */
    public ReservationResponse createInventoryReservationOnly(String productId, String customerId,
                                                              Integer quantity, String clientId) {
        try {
            InventoryReservation reservation = reservationService.reserveInventory(
                    productId, customerId, quantity, clientId
            );

            if (reservation == null) {
                return ReservationResponse.failed(productId, quantity,
                        "INSUFFICIENT_INVENTORY", "재고가 부족합니다");
            }

            reservationEventPublisher.publishReservationCreated(reservation);

            return ReservationResponse.success(
                    reservation.getReservationId(),
                    reservation.getProductId(),
                    reservation.getQuantity(),
                    reservation.getExpiresAt(),
                    reservation.getRemainingSeconds()
            );

        } catch (Exception e) {
            log.error("Error creating inventory reservation: productId={}, customerId={}",
                    productId, customerId, e);

            return ReservationResponse.failed(productId, quantity,
                    "SYSTEM_ERROR", "시스템 오류: " + e.getMessage());
        }
    }

    /**
     * 예약 상태 조회
     */
    public ReservationResponse getReservationStatus(String reservationId) {
        InventoryReservation reservation = reservationService.getReservation(reservationId);

        if (reservation == null) {
            return null;
        }

        return ReservationResponse.success(
                reservation.getReservationId(),
                reservation.getProductId(),
                reservation.getQuantity(),
                reservation.getExpiresAt(),
                reservation.getRemainingSeconds()
        );
    }

    /**
     * 통합 예약 상태 조회
     */
    public CompleteReservationResponse getCompleteReservationStatus(String reservationId) {
        try {
            String cacheKey = "complete_reservation:" + reservationId;
            Object cachedData = cacheService.getCachedData(cacheKey);

            if (cachedData != null) {
                return (CompleteReservationResponse) cachedData;
            }

            InventoryReservation reservation = reservationService.getReservation(reservationId);
            if (reservation == null) {
                return null;
            }

            return CompleteReservationResponse.builder()
                    .reservation(CompleteReservationResponse.ReservationInfo.builder()
                            .reservationId(reservationId)
                            .productId(reservation.getProductId())
                            .quantity(reservation.getQuantity())
                            .expiresAt(reservation.getExpiresAt())
                            .build())
                    .status("PARTIAL")
                    .message("부분 정보만 조회됨 (캐시 만료)")
                    .build();

        } catch (Exception e) {
            log.error("Error getting complete reservation status: reservationId={}", reservationId, e);
            return null;
        }
    }

    /**
     * 통합 예약 취소
     */
    public boolean cancelCompleteReservation(String reservationId, String customerId, String reason) {
        try {
            log.info("Cancelling complete reservation: reservationId={}, customerId={}, reason={}",
                    reservationId, customerId, reason);

            boolean cancelled = reservationService.cancelReservation(reservationId, customerId);

            if (cancelled) {
                reservationEventPublisher.publishReservationCancelled(reservationId, reason);

                String cacheKey = "complete_reservation:" + reservationId;
                cacheService.deleteCache(cacheKey);

                log.info("Complete reservation cancelled: reservationId={}", reservationId);
                return true;
            }

            return false;

        } catch (Exception e) {
            log.error("Error cancelling complete reservation: reservationId={}", reservationId, e);
            return false;
        }
    }

    // ========================================
    // 조회 API들
    // ========================================

    public List<ReservationResponse> getActiveReservationsByCustomer(String customerId, int page, int size) {
        log.debug("Getting active reservations for customer: customerId={}", customerId);
        return Collections.emptyList();
    }

    public List<CompleteReservationResponse> getCompleteReservationsByCustomer(String customerId, int page, int size) {
        log.debug("Getting complete reservations for customer: customerId={}", customerId);
        return Collections.emptyList();
    }

    public Map<String, Object> getReservationStatsByProduct(String productId) {
        log.debug("Getting reservation stats for product: productId={}", productId);
        return Map.of("productId", productId, "message", "통계 조회 기능 구현 예정");
    }

    public Map<String, Object> getSystemReservationStatus() {
        log.debug("Getting system reservation status");
        return Map.of("status", "OK", "message", "시스템 상태 조회 기능 구현 예정");
    }

    // ========================================
    // 보상 트랜잭션들 (Saga Pattern)
    // ========================================

    private void compensateReservation(String reservationId, String customerId) {
        try {
            reservationService.cancelReservation(reservationId, customerId);
            reservationEventPublisher.publishReservationCancelled(reservationId, "시스템 보상 트랜잭션");
            log.info("✅ Reservation compensated: reservationId={}", reservationId);
        } catch (Exception e) {
            log.error("❌ Failed to compensate reservation: reservationId={}", reservationId, e);
        }
    }

    private void compensateOrder(String orderId, String customerId) {
        try {
            orderService.cancelOrder(orderId, customerId, "시스템 보상");
            orderEventPublisher.publishOrderCancelled(orderId, "시스템 보상 트랜잭션");
            log.info("✅ Order compensated: orderId={}", orderId);
        } catch (Exception e) {
            log.error("❌ Failed to compensate order: orderId={}", orderId, e);
        }
    }

    private void compensatePayment(String paymentId) {
        try {
            paymentProcessingService.refundPayment(paymentId);
            log.info("✅ Payment compensated: paymentId={}", paymentId);
        } catch (Exception e) {
            log.error("❌ Failed to compensate payment: paymentId={}", paymentId, e);
        }
    }

    // ========================================
    // 이벤트 발행 및 캐싱
    // ========================================

    private void publishSuccessEvents(InventoryReservation reservation, Order order,
                                      Payment payment, String correlationId) {
        try {
            log.debug("Publishing success events: correlationId={}", correlationId);

            reservationEventPublisher.publishReservationConfirmed(
                    reservation.getReservationId(),
                    order.getOrderId(),
                    payment.getPaymentId()
            );

            orderEventPublisher.publishOrderStatusChanged(
                    order.getOrderId(),
                    "CREATED",
                    "PAID"
            );

            log.debug("All success events published");

        } catch (Exception e) {
            log.error("Error publishing success events: correlationId={}", correlationId, e);
        }
    }

    private void cacheCompleteResult(InventoryReservation reservation, Order order, Payment payment) {
        try {
            CompleteReservationResponse result = CompleteReservationResponse.builder()
                    .reservation(CompleteReservationResponse.ReservationInfo.builder()
                            .reservationId(reservation.getReservationId())
                            .productId(reservation.getProductId())
                            .quantity(reservation.getQuantity())
                            .expiresAt(reservation.getExpiresAt())
                            .build())
                    .order(CompleteReservationResponse.OrderInfo.builder()
                            .orderId(order.getOrderId())
                            .customerId(order.getCustomerId())
                            .status(order.getStatus().name())
                            .createdAt(order.getCreatedAt())
                            .build())
                    .payment(CompleteReservationResponse.PaymentInfo.builder()
                            .paymentId(payment.getPaymentId())
                            .transactionId(payment.getTransactionId())
                            .approvalNumber(payment.getApprovalNumber())
                            .amount(payment.getAmount().getAmount())
                            .currency(payment.getAmount().getCurrency())
                            .status(payment.getStatus().name())
                            .processedAt(payment.getProcessedAt())
                            .build())
                    .status("SUCCESS")
                    .message("통합 예약이 완료되었습니다")
                    .build();

            String cacheKey = "complete_reservation:" + reservation.getReservationId();
            cacheService.cacheData(cacheKey, result, 86400);

            log.debug("Complete result cached: reservationId={}", reservation.getReservationId());

        } catch (Exception e) {
            log.error("Error caching complete result", e);
        }
    }
}
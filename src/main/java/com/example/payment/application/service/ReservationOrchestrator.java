package com.example.payment.application.service;

import com.example.payment.application.event.publisher.OrderEventPublisher;
import com.example.payment.application.event.publisher.PaymentEventService;
import com.example.payment.application.event.publisher.ReservationEventPublisher;
import com.example.payment.application.service.OrderService.OrderCreationResult;
import com.example.payment.domain.model.inventory.InventoryConfirmation;
import com.example.payment.domain.model.order.Order;
import com.example.payment.domain.model.payment.Payment;
import com.example.payment.domain.model.reservation.InventoryReservation;
import com.example.payment.infrastructure.persistence.redis.repository.CacheService;
import com.example.payment.infrastructure.persistence.wal.WalService;
import com.example.payment.infrastructure.util.IdGenerator;
import com.example.payment.presentation.dto.request.CompleteReservationRequest;
import com.example.payment.presentation.dto.response.CompleteReservationResponse;
import com.example.payment.presentation.dto.response.ReservationResponse;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 실제 프로젝트 구조에 맞춘 최종 수정본
 * - CompleteReservationRequest.paymentInfo 사용
 * - Payment.getAmount().getAmount() 사용 (Money 객체)
 * - Payment.isCompleted() 사용
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class ReservationOrchestrator {

    // 도메인 서비스들
    private final ReservationService reservationService;
    private final OrderService orderService;
    private final PaymentProcessingService paymentProcessingService;
    private final InventoryManagementService inventoryManagementService;

    // 인프라 서비스들
    private final CacheService cacheService;
    private final WalService walService;

    // 이벤트 퍼블리셔들
    private final ReservationEventPublisher reservationEventPublisher;
    private final OrderEventPublisher orderEventPublisher;
    private final PaymentEventService paymentEventService;

    /**
     * 통합 예약 플로우 - 실제 프로젝트 구조 반영
     */
    public CompleteReservationResponse processCompleteReservation(CompleteReservationRequest request) {
        String transactionId = request.getCorrelationId() != null ?
                request.getCorrelationId() : IdGenerator.generateCorrelationId();

        log.info("Starting complete reservation flow: txId={}, customerId={}, productId={}, quantity={}",
                transactionId, request.getCustomerId(), request.getProductId(), request.getQuantity());

        Map<String, String> walLogIds = new HashMap<>();

        try {
            // ===================================
            // Phase 1: 재고 선점
            // ===================================
            log.debug("[Phase 1] Step 1: Reserve inventory (txId={})", transactionId);

            InventoryReservation reservation = reservationService.reserveInventory(
                    transactionId,
                    request.getProductId(),
                    request.getCustomerId(),
                    request.getQuantity(),
                    request.getClientId()
            );

            if (reservation == null) {
                log.warn("[Phase 1] Reservation failed: txId={}, insufficient inventory", transactionId);
                reservationEventPublisher.publishReservationCancelled(
                        "TEMP-" + IdGenerator.generateReservationId(),
                        "재고 부족"
                );
                return CompleteReservationResponse.failed("재고 선점 실패: 재고가 부족합니다");
            }

            log.info("[Phase 1] Reservation succeeded: txId={}, reservationId={}",
                    transactionId, reservation.getReservationId());
            reservationEventPublisher.publishReservationCreated(reservation);

            // ===================================
            // Phase 1: 주문 생성
            // ===================================
            log.debug("[Phase 1] Step 2: Create order (txId={})", transactionId);

            OrderCreationResult orderResult = orderService.createOrder(
                    transactionId,
                    request.getCustomerId(),
                    request.getProductId(),
                    request.getQuantity(),
                    request.getPaymentInfo().getAmount(),
                    request.getPaymentInfo().getCurrency(),
                    reservation.getReservationId()
            );

            Order order = orderResult.getOrder();
            String orderPhase1LogId = orderResult.getPhase1WalLogId();
            walLogIds.put("ORDER_PHASE1", orderPhase1LogId);

            log.info("[Phase 1] Order created: txId={}, orderId={}, phase1LogId={}",
                    transactionId, order.getOrderId(), orderPhase1LogId);

            // publishOrderCreated 호출
            orderEventPublisher.publishOrderCreated(
                    order.getOrderId(),
                    order.getCustomerId(),
                    order.getReservationId()
            );

            // ===================================
            // PG 연동 결제 처리
            // ===================================
            log.debug("[PG Integration] Processing payment (txId={})", transactionId);
            String paymentId = IdGenerator.generatePaymentId();

            // processPayment 호출 (8개 파라미터)
            Payment payment = paymentProcessingService.processPayment(
                    transactionId,  // 1. transactionId
                    paymentId,      // 2
                    order.getOrderId(),  // 3
                    reservation.getReservationId(),  // 4
                    request.getCustomerId(),  // 5
                    request.getPaymentInfo().getAmount(),  // 6
                    request.getPaymentInfo().getCurrency(),  // 7
                    request.getPaymentInfo().getPaymentMethod()  // 8
            );

            if (payment == null || !payment.isCompleted()) {
                log.warn("[PG Integration] Payment failed: txId={}, orderId={}, status={}",
                        transactionId, order.getOrderId(),
                        payment != null ? payment.getStatus() : "null");

                compensateReservation(transactionId, reservation.getReservationId(), request.getCustomerId());
                compensateOrder(transactionId, order.getOrderId(), request.getCustomerId());

                return CompleteReservationResponse.failed("결제 실패: " +
                        (payment != null ? payment.getFailureReason() : "알 수 없음"));
            }

            log.info("[PG Integration] Payment succeeded: txId={}, paymentId={}",
                    transactionId, payment.getPaymentId());
            paymentEventService.publishPaymentProcessed(payment);

            // ===================================
            // Phase 2: 재고 확정
            // ===================================
            log.debug("[Phase 2] Step 1: Confirm inventory (txId={})", transactionId);

            // ConfirmReservation 호출 (5개 파라미터)
            // TODO: 향후 ReservationService 개선 시 phase1LogId 받아오기
            String reservationPhase1LogId = null;

            InventoryConfirmation confirmation = inventoryManagementService.confirmReservation(
                    transactionId,                    // 1. transactionId
                    reservationPhase1LogId,           // 2. phase1LogId (현재 null)
                    reservation.getReservationId(),   // 3. reservationId
                    order.getOrderId(),               // 4. orderId
                    payment.getPaymentId()            // 5. paymentId
            );

            if (confirmation == null || !confirmation.isSuccess()) {
                log.warn("[Phase 2] Inventory confirmation failed: txId={}, reservationId={}",
                        transactionId, reservation.getReservationId());

                compensatePayment(transactionId, payment.getPaymentId());
                compensateOrder(transactionId, order.getOrderId(), request.getCustomerId());

                return CompleteReservationResponse.failed("재고 확정 실패");
            }

            log.info("[Phase 2] Inventory confirmed: txId={}, reservationId={}",
                    transactionId, reservation.getReservationId());

            // ===================================
            // Phase 2: 주문 결제 완료 처리
            // ===================================
            log.debug("[Phase 2] Step 2: Mark order as paid (txId={})", transactionId);

            boolean orderUpdated = orderService.markOrderAsPaid(
                    transactionId,
                    orderPhase1LogId,
                    order.getOrderId(),
                    payment.getPaymentId()
            );

            if (!orderUpdated) {
                log.warn("[Phase 2] Order payment update failed: txId={}, orderId={}",
                        transactionId, order.getOrderId());

                compensatePayment(transactionId, payment.getPaymentId());
                inventoryManagementService.rollbackReservation(
                        transactionId,
                        reservation.getReservationId(),
                        order.getOrderId(),
                        "주문 업데이트 실패"
                );

                return CompleteReservationResponse.failed("주문 업데이트 실패");
            }

            log.info("[Phase 2] Order marked as paid: txId={}, orderId={}",
                    transactionId, order.getOrderId());

            // ===================================
            // 성공 응답 생성 및 이벤트 발행
            // ===================================
            publishSuccessEvents(transactionId, reservation, order, payment);

            CompleteReservationResponse response = buildSuccessResponse(
                    reservation, order, payment, transactionId
            );

            cacheCompleteReservation(transactionId, reservation.getReservationId(), response);

            log.info("Complete reservation flow finished successfully: txId={}, reservationId={}, orderId={}, paymentId={}",
                    transactionId, reservation.getReservationId(), order.getOrderId(), payment.getPaymentId());

            return response;

        } catch (Exception e) {
            log.error("System error in complete reservation flow: txId={}, customerId={}, productId={}",
                    transactionId, request.getCustomerId(), request.getProductId(), e);

            return CompleteReservationResponse.failed("시스템 오류: " + e.getMessage());
        }
    }

    /**
     * 재고 선점만 (Phase 1만)
     */
    public ReservationResponse createInventoryReservationOnly(
            String productId,
            String customerId,
            Integer quantity,
            String clientId) {

        // 트랜잭션 ID 생성
        String transactionId = IdGenerator.generateCorrelationId();

        try {
            log.info("Creating inventory reservation only: txId={}, productId={}, customerId={}",
                    transactionId, productId, customerId);

            InventoryReservation reservation = reservationService.reserveInventory(
                    transactionId,
                    productId,
                    customerId,
                    quantity,
                    clientId
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
                    Long.valueOf(reservation.getRemainingSeconds())
            );

        } catch (Exception e) {
            log.error("Error creating inventory reservation: txId={}, productId={}, customerId={}",
                    transactionId, productId, customerId, e);

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
                Long.valueOf(reservation.getRemainingSeconds())
        );
    }

    /**
     * 통합 예약 상태 조회
     */
    public CompleteReservationResponse getCompleteReservationStatus(String reservationId) {
        try {
            String cacheKey = "complete_reservation:" + reservationId;
            Object cachedData = cacheService.getCachedData(cacheKey);

            if (cachedData != null && cachedData instanceof CompleteReservationResponse) {
                return (CompleteReservationResponse) cachedData;
            }

            // 캐시에 없으면 부분 정보만 조회
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
        String transactionId = IdGenerator.generateCorrelationId();

        try {
            log.info("Cancelling complete reservation: txId={}, reservationId={}, customerId={}, reason={}",
                    transactionId, reservationId, customerId, reason);

            boolean cancelled = reservationService.cancelReservation(transactionId, reservationId, customerId);

            if (cancelled) {
                reservationEventPublisher.publishReservationCancelled(reservationId, reason);

                String cacheKey = "complete_reservation:" + reservationId;
                cacheService.deleteCache(cacheKey);

                log.info("Complete reservation cancelled: txId={}, reservationId={}",
                        transactionId, reservationId);
                return true;
            }

            return false;

        } catch (Exception e) {
            log.error("Error cancelling complete reservation: reservationId={}", reservationId, e);
            return false;
        }
    }

    // ===================================
    // 보상 트랜잭션들 (Saga Pattern)
    // ===================================

    private void compensateReservation(String transactionId, String reservationId, String customerId) {
        try {
            log.info("[Compensation] Compensating reservation: txId={}, reservationId={}",
                    transactionId, reservationId);

            reservationService.cancelReservation(transactionId, reservationId, customerId);
            reservationEventPublisher.publishReservationCancelled(reservationId, "시스템 보상 트랜잭션");

            log.info("[Compensation] Reservation compensated: txId={}, reservationId={}",
                    transactionId, reservationId);
        } catch (Exception e) {
            log.error("[Compensation] Failed to compensate reservation: txId={}, reservationId={}",
                    transactionId, reservationId, e);
        }
    }

    private void compensateOrder(String transactionId, String orderId, String customerId) {
        try {
            log.info("[Compensation] Compensating order: txId={}, orderId={}",
                    transactionId, orderId);

            orderService.cancelOrder(transactionId, orderId, customerId, "시스템 보상");
            orderEventPublisher.publishOrderCancelled(orderId, "시스템 보상 트랜잭션");

            log.info("[Compensation] Order compensated: txId={}, orderId={}",
                    transactionId, orderId);
        } catch (Exception e) {
            log.error("[Compensation] Failed to compensate order: txId={}, orderId={}",
                    transactionId, orderId, e);
        }
    }

    private void compensatePayment(String transactionId, String paymentId) {
        try {
            log.info("[Compensation] Compensating payment: txId={}, paymentId={}",
                    transactionId, paymentId);

            paymentProcessingService.refundPayment(paymentId);

            log.info("[Compensation] Payment compensated: txId={}, paymentId={}",
                    transactionId, paymentId);
        } catch (Exception e) {
            log.error("[Compensation] Failed to compensate payment: txId={}, paymentId={}",
                    transactionId, paymentId, e);
        }
    }

    // ===================================
    // Helper Methods
    // ===================================

    private void publishSuccessEvents(String transactionId, InventoryReservation reservation,
                                      Order order, Payment payment) {
        try {
            log.debug("Publishing success events: txId={}", transactionId);

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

            log.debug("All success events published: txId={}", transactionId);

        } catch (Exception e) {
            log.error("Error publishing success events: txId={}", transactionId, e);
        }
    }

    private CompleteReservationResponse buildSuccessResponse(
            InventoryReservation reservation,
            Order order,
            Payment payment,
            String transactionId) {

        return CompleteReservationResponse.builder()
                .status("SUCCESS")
                .message("예약이 완료되었습니다")
                // 예약 정보
                .reservation(CompleteReservationResponse.ReservationInfo.builder()
                        .reservationId(reservation.getReservationId())
                        .productId(reservation.getProductId())
                        .quantity(reservation.getQuantity())
                        .expiresAt(reservation.getExpiresAt())
                        .build())
                // 주문 정보
                .order(CompleteReservationResponse.OrderInfo.builder()
                        .orderId(order.getOrderId())
                        .customerId(order.getCustomerId())
                        .status(order.getStatus().name())
                        .createdAt(order.getCreatedAt())
                        .build())
                // 결제 정보 - Payment.getAmount().getAmount() 사용 (Money 객체)
                .payment(CompleteReservationResponse.PaymentInfo.builder()
                        .paymentId(payment.getPaymentId())
                        .transactionId(payment.getTransactionId())
                        .approvalNumber(payment.getApprovalNumber())
                        .amount(payment.getAmount().getAmount())      //  Money.getAmount()
                        .currency(payment.getAmount().getCurrency())  //  Money.getCurrency()
                        .status(payment.getStatus().name())
                        .processedAt(payment.getProcessedAt())
                        .build())
                .build();
    }

    private void cacheCompleteReservation(String transactionId, String reservationId,
                                          CompleteReservationResponse response) {
        try {
            String cacheKey = "complete_reservation:" + reservationId;
            cacheService.cacheData(cacheKey, response, 3600); // 1시간

            // 트랜잭션 ID로도 캐싱 (조회 편의성)
            String txCacheKey = "tx_complete_reservation:" + transactionId;
            cacheService.cacheData(txCacheKey, reservationId, 3600);

            log.debug("Complete reservation cached: txId={}, reservationId={}",
                    transactionId, reservationId);
        } catch (Exception e) {
            log.warn("Failed to cache complete reservation: txId={}", transactionId, e);
        }
    }

    // ===================================
    // 조회 메서드들 (기존 유지)
    // ===================================

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
}
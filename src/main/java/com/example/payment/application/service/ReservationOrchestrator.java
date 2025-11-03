package com.example.payment.application.service;

import com.example.payment.application.event.publisher.OrderEventPublisher;
import com.example.payment.application.event.publisher.PaymentEventService;
import com.example.payment.application.event.publisher.ReservationEventPublisher;
import com.example.payment.application.service.OrderService.OrderCreationResult;
// [추가] 1. ReservationService의 내부 클래스 임포트 (문제 2.B)
import com.example.payment.application.service.ReservationService.ReservationResult;
import com.example.payment.domain.model.inventory.InventoryConfirmation;
import com.example.payment.domain.model.order.Order;
import com.example.payment.domain.model.payment.Payment;
import com.example.payment.domain.model.reservation.InventoryReservation;
import com.example.payment.infrastructure.persistence.redis.repository.CacheService;
import com.example.payment.infrastructure.persistence.wal.WalService;
import com.example.payment.infrastructure.util.IdGenerator;
import com.example.payment.presentation.dto.request.CompleteReservationRequest;
// [추가] 2. CompleteReservationResponse 임포트 (문제 2.A)
import com.example.payment.presentation.dto.response.CompleteReservationResponse;
import com.example.payment.presentation.dto.response.ReservationResponse;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 실제 프로젝트 구조에 맞춘 최종 수정본
 * - [수정] WAL 체인 연결 및 통합 예약 취소 로직 수정 (문제 2.A, 2.B 해결)
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
     * [수정됨] 2.B: ReservationResult를 받아 walLogId를 확정 단계로 전달
     */
    public CompleteReservationResponse processCompleteReservation(CompleteReservationRequest request) {
        String transactionId = request.getCorrelationId() != null ?
                request.getCorrelationId() : IdGenerator.generateCorrelationId();

        log.info("Starting complete reservation flow: txId={}, customerId={}, productId={}, quantity={}",
                transactionId, request.getCustomerId(), request.getProductId(), request.getQuantity());

        Map<String, String> walLogIds = new HashMap<>();

        // [수정] 3. try-catch 블록을 세분화하여 보상 트랜잭션이 명확히 실행되도록 함
        ReservationResult reservationResult = null;
        OrderCreationResult orderResult = null;
        Payment payment = null;

        try {
            // ===================================
            // Phase 1: 재고 선점
            // ===================================
            log.debug("[Phase 1] Step 1: Reserve inventory (txId={})", transactionId);

            // [수정] 4. 반환 타입을 ReservationResult로 변경
            reservationResult = reservationService.reserveInventory(
                    transactionId,
                    request.getProductId(),
                    request.getCustomerId(),
                    request.getQuantity(),
                    request.getClientId()
            );

            if (reservationResult == null) {
                log.warn("[Phase 1] Reservation failed: txId={}, insufficient inventory", transactionId);
                reservationEventPublisher.publishReservationCancelled(
                        "TEMP-" + IdGenerator.generateReservationId(),
                        "재고 부족"
                );
                return CompleteReservationResponse.failed("재고 선점 실패: 재고가 부족합니다");
            }

            // [수정] 5. 객체 및 walLogId 추출
            InventoryReservation reservation = reservationResult.getReservation();
            String reservationPhase1LogId = reservationResult.getWalLogId();
            walLogIds.put("RESERVATION_PHASE1", reservationPhase1LogId);

            log.info("[Phase 1] Reservation succeeded: txId={}, reservationId={}, phase1LogId={}",
                    transactionId, reservation.getReservationId(), reservationPhase1LogId);
            reservationEventPublisher.publishReservationCreated(reservation);

            // ===================================
            // Phase 1: 주문 생성
            // ===================================
            log.debug("[Phase 1] Step 2: Create order (txId={})", transactionId);

            orderResult = orderService.createOrder(
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

            payment = paymentProcessingService.processPayment(
                    transactionId,
                    paymentId,
                    order.getOrderId(),
                    reservation.getReservationId(),
                    request.getCustomerId(),
                    request.getPaymentInfo().getAmount(),
                    request.getPaymentInfo().getCurrency(),
                    request.getPaymentInfo().getPaymentMethod()
            );

            if (payment == null || !payment.isCompleted()) {
                log.warn("[PG Integration] Payment failed: txId={}, orderId={}, status={}",
                        transactionId, order.getOrderId(),
                        payment != null ? payment.getStatus() : "null");

                // [수정] 결제 실패 시 즉시 보상 트랜잭션 실행
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

            InventoryConfirmation confirmation = inventoryManagementService.confirmReservation(
                    transactionId,
                    reservationPhase1LogId,           // [수정] 6. null 대신 실제 walLogId 전달
                    reservation.getReservationId(),
                    order.getOrderId(),
                    payment.getPaymentId()
            );

            if (confirmation == null || !confirmation.isSuccess()) {
                log.warn("[Phase 2] Inventory confirmation failed: txId={}, reservationId={}",
                        transactionId, reservation.getReservationId());

                // [수정] 재고 확정 실패 시 결제/주문 보상
                compensatePayment(transactionId, payment.getPaymentId());
                compensateOrder(transactionId, order.getOrderId(), request.getCustomerId());
                // (재고는 이미 1단계에서 선점되었으므로 롤백할 필요 없음. 단, 재고 확정이 실패했으므로 수동 개입 필요)

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

                // [수정] 주문 확정 실패 시 결제 보상 및 재고 롤백
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

            // [수정] 7. SAGA 진행 단계에 따라 보상 트랜잭션 실행
            if (payment != null && payment.isCompleted()) {
                log.error("[Compensation] System error after payment success. Triggering compensation for payment/order/inventory.");
                compensatePayment(transactionId, payment.getPaymentId());
                if (orderResult != null) {
                    compensateOrder(transactionId, orderResult.getOrder().getOrderId(), request.getCustomerId());
                }
                if (reservationResult != null) {
                    inventoryManagementService.rollbackReservation(
                            transactionId,
                            reservationResult.getReservation().getReservationId(),
                            (orderResult != null) ? orderResult.getOrder().getOrderId() : null,
                            "System Error Recovery"
                    );
                }
            } else if (orderResult != null) {
                log.error("[Compensation] System error after order creation. Triggering compensation for order/reservation.");
                compensateOrder(transactionId, orderResult.getOrder().getOrderId(), request.getCustomerId());
                if (reservationResult != null) {
                    compensateReservation(transactionId, reservationResult.getReservation().getReservationId(), request.getCustomerId());
                }
            } else if (reservationResult != null) {
                log.error("[Compensation] System error after reservation. Triggering compensation for reservation.");
                compensateReservation(transactionId, reservationResult.getReservation().getReservationId(), request.getCustomerId());
            }

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

            // [수정] 8. ReservationResult 사용
            ReservationResult reservationResult = reservationService.reserveInventory(
                    transactionId,
                    productId,
                    customerId,
                    quantity,
                    clientId
            );

            if (reservationResult == null) {
                return ReservationResponse.failed(productId, quantity,
                        "INSUFFICIENT_INVENTORY", "재고가 부족합니다");
            }

            InventoryReservation reservation = reservationResult.getReservation();
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
            // [수정] 9. 캐시 타입 명시
            CompleteReservationResponse cachedData = cacheService.getCachedObject(cacheKey, CompleteReservationResponse.class);

            if (cachedData != null) {
                return cachedData;
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
     * [수정됨] 2.A: 재고, 주문, 결제(환불)를 모두 보상 트랜잭션 처리
     */
    public boolean cancelCompleteReservation(String reservationId, String customerId, String reason) {
        String transactionId = IdGenerator.generateCorrelationId();

        try {
            log.info("Cancelling complete reservation: txId={}, reservationId={}, customerId={}, reason={}",
                    transactionId, reservationId, customerId, reason);

            // 1. 재고 선점 취소
            boolean reservationCancelled = reservationService.cancelReservation(transactionId, reservationId, customerId);

            if (reservationCancelled) {
                reservationEventPublisher.publishReservationCancelled(reservationId, reason);
                log.info("Step 1/3: Reservation cancelled: txId={}, reservationId={}", transactionId, reservationId);
            } else {
                log.warn("Failed to cancel reservation (already cancelled or invalid?): reservationId={}", reservationId);
                // 재고가 이미 취소되었어도, 주문/결제는 취소/환불 시도
            }

            // 2. 캐시에서 OrderId, PaymentId 조회 (가장 중요!)
            CompleteReservationResponse cachedResponse = getCompleteReservationStatus(reservationId);
            String orderId = null;
            String paymentId = null;

            if (cachedResponse != null && cachedResponse.getOrder() != null && cachedResponse.getPayment() != null) {
                orderId = cachedResponse.getOrder().getOrderId();
                paymentId = cachedResponse.getPayment().getPaymentId();
            } else {
                log.warn("Could not find orderId/paymentId from cache for reservationId: {}. " +
                        "Only reservation stock will be cancelled.", reservationId);
                // (개선) 이 경우 DB에서 reservationId로 Order/Payment를 조회하는 로직이 필요.
                return reservationCancelled; // 재고 취소 결과만 반환
            }

            // 3. 주문(Order) 취소
            if (orderId != null) {
                orderService.cancelOrder(transactionId, orderId, customerId, reason);
                log.info("Step 2/3: Order cancelled: txId={}, orderId={}", transactionId, orderId);
            }

            // 4. 결제(Payment) 환불
            if (paymentId != null) {
                paymentProcessingService.refundPayment(paymentId);
                log.info("Step 3/3: Payment refund processed: txId={}, paymentId={}", transactionId, paymentId);
            }

            // 5. 통합 캐시 삭제
            String cacheKey = "complete_reservation:" + reservationId;
            cacheService.deleteCache(cacheKey);

            log.info("Complete reservation cancellation finished: txId={}, reservationId={}",
                    transactionId, reservationId);
            return true;

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
                .correlationId(transactionId) // [추가] 응답에 트랜잭션 ID 포함
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
            // [수정] 10. cacheData 대신 cacheObject 사용
            cacheService.cacheObject(cacheKey, response, Duration.ofHours(1)); // 1시간

            // 트랜잭션 ID로도 캐싱 (조회 편의성)
            String txCacheKey = "tx_complete_reservation:" + transactionId;
            cacheService.cacheObject(txCacheKey, reservationId, Duration.ofHours(1));

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
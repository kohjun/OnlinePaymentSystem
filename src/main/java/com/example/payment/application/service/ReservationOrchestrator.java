package com.example.payment.application.service;

import com.example.payment.domain.model.inventory.InventoryConfirmation;
import com.example.payment.domain.model.order.Order;
import com.example.payment.domain.model.payment.Payment;
import com.example.payment.domain.model.reservation.InventoryReservation;
import com.example.payment.infrastructure.persistence.redis.repository.CacheService;
import com.example.payment.infrastructure.util.IdGenerator;
import com.example.payment.presentation.dto.request.CompleteReservationRequest;
import com.example.payment.presentation.dto.response.CompleteReservationResponse;
import com.example.payment.presentation.dto.response.ReservationResponse;  // 새 DTO 사용
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

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
    private final CacheService cacheService;

    /**
     * 통합 예약 플로우 - WAL 2단계 방식 (새 DTO 구조 적용)
     */
    public CompleteReservationResponse processCompleteReservation(CompleteReservationRequest request) {

        log.info("Starting complete reservation flow with improved DTO: customerId={}, productId={}, quantity={}, amount={}",
                request.getCustomerId(), request.getProductId(), request.getQuantity(),
                request.getPaymentInfo().getAmount());

        String correlationId = request.getCorrelationId() != null ?
                request.getCorrelationId() : IdGenerator.generateCorrelationId();

        try {
            // ========================================
            // Phase 1: 재고 선점 + 주문 생성 (즉시 처리)
            // ========================================

            // 1-1. 재고 선점 (WAL Phase 1)
            InventoryReservation reservation = reservationService.reserveInventory(
                    request.getProductId(),
                    request.getCustomerId(),
                    request.getQuantity(),
                    request.getClientId()
            );

            if (reservation == null) {
                return CompleteReservationResponse.failed("재고 선점 실패: 재고가 부족합니다");
            }

            // 1-2. 주문 생성 (WAL Phase 1)
            Order order;
            try {
                order = orderService.createOrder(
                        request.getCustomerId(),
                        request.getProductId(),
                        request.getQuantity(),
                        request.getPaymentInfo().getAmount(),     // 새 구조 사용
                        request.getPaymentInfo().getCurrency(),   // 새 구조 사용
                        reservation.getReservationId()
                );

            } catch (Exception e) {
                // 보상: 재고 예약 취소
                compensateReservation(reservation.getReservationId(), request.getCustomerId());
                return CompleteReservationResponse.failed("주문 생성 실패: " + e.getMessage());
            }

            // ========================================
            // 외부 결제 처리 (PG 연동)
            // ========================================

            Payment payment;
            try {
                String paymentId = IdGenerator.generatePaymentId();

                payment = paymentProcessingService.processPayment(
                        paymentId,
                        order.getOrderId(),
                        reservation.getReservationId(),
                        request.getCustomerId(),
                        request.getPaymentInfo().getAmount(),       // 새 구조 사용
                        request.getPaymentInfo().getCurrency(),     // 새 구조 사용
                        request.getPaymentInfo().getPaymentMethod() // 새 구조 사용
                );

                if (!payment.isCompleted()) {
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
            // Phase 2: 확정 처리 (결제 성공 후)
            // ========================================

            try {
                // 2-1. 예약 확정 (WAL Phase 2)
                boolean reservationConfirmed = reservationService.confirmReservationWithWal(
                        reservation.getReservationId(),
                        request.getCustomerId(),
                        order.getOrderId(),
                        payment.getPaymentId()
                );

                if (!reservationConfirmed) {
                    log.error("Reservation confirmation failed: reservationId={}", reservation.getReservationId());
                    // 보상: 결제 환불 + 주문 취소 + 재고 예약 취소
                    compensatePayment(payment.getPaymentId());
                    compensateOrder(order.getOrderId(), request.getCustomerId());
                    compensateReservation(reservation.getReservationId(), request.getCustomerId());
                    return CompleteReservationResponse.failed("예약 확정 실패");
                }

                // 2-2. 주문 결제 완료 처리 (WAL Phase 2)
                boolean orderUpdated = orderService.updateOrderToPaidWithWal(
                        order.getOrderId(),
                        payment.getPaymentId()
                );

                if (!orderUpdated) {
                    log.error("Order payment update failed: orderId={}", order.getOrderId());
                    // 이미 예약은 확정되었으므로, 주문 상태만 수동으로 처리하도록 알림
                    // 실제로는 별도의 복구 프로세스가 필요
                }

                // 2-3. 재고 확정 (최종 단계)
                InventoryConfirmation confirmation = inventoryManagementService.confirmReservation(
                        reservation.getReservationId(),
                        order.getOrderId(),
                        payment.getPaymentId()
                );

                if (!confirmation.isConfirmed()) {
                    log.warn("Inventory confirmation completed with issues: {}", confirmation.getReason());
                    // 이미 결제와 예약이 완료되었으므로, 경고 로그만 남김
                }

            } catch (Exception e) {
                log.error("Error in Phase 2 processing", e);
                // Phase 2 실패는 이미 결제가 완료된 상태이므로 보상보다는 복구가 필요
                // 별도의 복구 프로세스나 수동 처리가 필요
                return CompleteReservationResponse.failed("확정 처리 실패: " + e.getMessage());
            }

            // ========================================
            // 성공 처리 - 새로운 구조화된 응답 생성
            // ========================================

            publishSuccessEvents(reservation, order, payment, correlationId);
            cacheCompleteResult(reservation, order, payment);

            log.info("Complete reservation succeeded with WAL: reservationId={}, orderId={}, paymentId={}",
                    reservation.getReservationId(), order.getOrderId(), payment.getPaymentId());

            // 새로운 구조화된 통합 Response 생성
            return CompleteReservationResponse.success(
                    reservation.getReservationId(),
                    order.getOrderId(),
                    payment.getPaymentId(),
                    payment.getTransactionId(),
                    request.getProductId(),
                    request.getQuantity(),
                    request.getPaymentInfo().getAmount(),   // 새 구조 사용
                    request.getPaymentInfo().getCurrency()  // 새 구조 사용
            );

        } catch (Exception e) {
            log.error("Complete reservation failed: customerId={}, productId={}",
                    request.getCustomerId(), request.getProductId(), e);

            return CompleteReservationResponse.failed("시스템 오류: " + e.getMessage());
        }
    }

    /**
     * 개별 재고 예약만 처리 - 새 DTO 구조 사용
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
     * 예약 상태 조회 - 새 DTO 구조 사용
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
     * 통합 예약 상태 조회 - 새 DTO 구조 사용
     */
    public CompleteReservationResponse getCompleteReservationStatus(String reservationId) {
        try {
            // 캐시에서 통합 결과 조회
            String cacheKey = "complete_reservation:" + reservationId;
            Object cachedData = cacheService.getCachedData(cacheKey);

            if (cachedData != null) {
                return (CompleteReservationResponse) cachedData;
            }

            // 캐시에 없으면 개별 조회해서 조합
            InventoryReservation reservation = reservationService.getReservation(reservationId);
            if (reservation == null) {
                return null;
            }

            // 부분 정보로 응답 생성 (새 구조 사용)
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

            // 예약 취소 (다른 연관 데이터도 함께 정리됨)
            boolean cancelled = reservationService.cancelReservation(reservationId, customerId);

            if (cancelled) {
                // 캐시 정리
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
    // 조회 API들 - 새 DTO 구조 사용
    // ========================================

    public List<ReservationResponse> getActiveReservationsByCustomer(String customerId, int page, int size) {
        // TODO: 실제 구현 필요
        log.debug("Getting active reservations for customer: customerId={}", customerId);
        return Collections.emptyList();
    }

    public List<CompleteReservationResponse> getCompleteReservationsByCustomer(String customerId, int page, int size) {
        // TODO: 실제 구현 필요
        log.debug("Getting complete reservations for customer: customerId={}", customerId);
        return Collections.emptyList();
    }

    public Map<String, Object> getReservationStatsByProduct(String productId) {
        // TODO: 실제 구현 필요
        log.debug("Getting reservation stats for product: productId={}", productId);
        return Map.of(
                "productId", productId,
                "message", "통계 조회 기능 구현 예정"
        );
    }

    public Map<String, Object> getSystemReservationStatus() {
        // TODO: 실제 구현 필요
        log.debug("Getting system reservation status");
        return Map.of(
                "status", "OK",
                "message", "시스템 상태 조회 기능 구현 예정"
        );
    }

    // ========================================
    // 보상 트랜잭션들
    // ========================================

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
            orderService.cancelOrder(orderId, customerId, "시스템 보상");
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

    // ========================================
    // 이벤트 발행 및 캐싱 - 새 DTO 구조 사용
    // ========================================

    private void publishSuccessEvents(InventoryReservation reservation, Order order,
                                      Payment payment, String correlationId) {
        try {
            // Kafka 이벤트 발행 로직 (구현 예정)
            log.debug("Publishing success events: correlationId={}", correlationId);

            // TODO: 새 DTO 구조를 사용한 이벤트 발행
            // PaymentEventService를 통해 새로운 구조화된 이벤트 발행

        } catch (Exception e) {
            log.error("Error publishing success events", e);
        }
    }

    private void cacheCompleteResult(InventoryReservation reservation, Order order, Payment payment) {
        try {
            // 새로운 구조화된 통합 결과를 캐시에 저장
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
            cacheService.cacheData(cacheKey, result, 86400); // 24시간

            log.debug("Complete result cached with new structure: reservationId={}",
                    reservation.getReservationId());

        } catch (Exception e) {
            log.error("Error caching complete result", e);
        }
    }
}
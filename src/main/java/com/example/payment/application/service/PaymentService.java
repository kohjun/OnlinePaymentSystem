/**
 * ========================================
 * 고성능 OLTP용 결제 서비스 (패턴 B 전용)
 * ========================================
 * 플로우: 예약 검증 → PG 호출 → 결과 처리 → 주문 생성
 */
package com.example.payment.application.service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.example.payment.infrastructure.persistance.redis.repository.CacheService;
import com.example.payment.application.event.publisher.PaymentEventService;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.payment.presentation.dto.request.PaymentRequest;
import com.example.payment.presentation.dto.response.PaymentResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentService {

    private final CacheService cacheService;
    private final InventoryManagementService inventoryService;
    private final OrderService orderService;
    private final PaymentEventService eventService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    // 고성능 설정
    private static final String PAYMENT_EVENTS_TOPIC = "payment-events";

    /**
     * 예약된 상품 결제 처리 (패턴 B 전용)
     * Phase 1: 예약 검증 → Phase 2: PG 호출 → Phase 3: 결과 처리
     */
    public PaymentResponse processReservationPayment(PaymentRequest request) {
        log.info("Processing payment for reservation: paymentId={}, reservationId={}",
                request.getPaymentId(), request.getReservationId());

        try {
            // Phase 1: 예약 상태 검증 (빠른 트랜잭션)
            ReservationValidationResult validation = validateReservation(request);
            if (!validation.isValid()) {
                return createFailureResponse(request, validation.getErrorMessage());
            }

            log.info("Phase 1 completed - Reservation validated: reservationId={}",
                    request.getReservationId());

            // Phase 2: PG 결제 처리 (트랜잭션 외부)
            PaymentGatewayResult gatewayResult = processPaymentGateway(request);

            log.info("Phase 2 completed - Payment gateway result: paymentId={}, success={}",
                    request.getPaymentId(), gatewayResult.isSuccess());

            // Phase 3: 결제 결과 처리 (트랜잭션)
            PaymentResponse response = finalizePayment(request, validation, gatewayResult);

            log.info("Phase 3 completed - Payment finalized: paymentId={}, status={}",
                    request.getPaymentId(), response.getStatus());

            // Phase 4: 이벤트 발행 (트랜잭션 완료 후)
            publishPaymentEvents(response, validation.getReservation());

            return response;

        } catch (Exception e) {
            log.error("Error in payment processing: paymentId={}, error={}",
                    request.getPaymentId(), e.getMessage(), e);

            // 보상 처리
            compensateFailedPayment(request);

            return createErrorResponse(request, e);
        }
    }

    /**
     * Phase 1: 예약 상태 검증 (빠른 트랜잭션)
     */
    @Transactional(timeout = 3)
    private ReservationValidationResult validateReservation(PaymentRequest request) {
        try {
            // 예약 정보 조회
            String cacheKey = "reservation:" + request.getReservationId();
            Object reservationData = cacheService.getCachedData(cacheKey);

            if (reservationData == null) {
                return ReservationValidationResult.invalid("예약 정보를 찾을 수 없습니다");
            }

            ReservationState reservation;
            if (reservationData instanceof ReservationState) {
                reservation = (ReservationState) reservationData;
            } else {
                reservation = objectMapper.convertValue(reservationData, ReservationState.class);
            }

            // 만료 시간 확인
            if (LocalDateTime.now().isAfter(reservation.getExpiresAt())) {
                cleanupExpiredReservation(request.getReservationId(), reservation);
                return ReservationValidationResult.invalid("예약이 만료되었습니다");
            }

            // 상태 확인
            if (!"RESERVED".equals(reservation.getStatus())) {
                return ReservationValidationResult.invalid("잘못된 예약 상태: " + reservation.getStatus());
            }

            // 고객 일치 확인
            if (!request.getCustomerId().equals(reservation.getCustomerId())) {
                return ReservationValidationResult.invalid("예약 소유자가 일치하지 않습니다");
            }

            return ReservationValidationResult.valid(reservation);

        } catch (Exception e) {
            log.error("Error validating reservation: reservationId={}", request.getReservationId(), e);
            return ReservationValidationResult.invalid("예약 검증 중 오류 발생");
        }
    }

    /**
     * Phase 2: PG 결제 처리 (트랜잭션 외부)
     */
    private PaymentGatewayResult processPaymentGateway(PaymentRequest request) {
        try {
            // PG사 API 호출 (시뮬레이션)
            boolean success = processExternalPayment(request);

            return PaymentGatewayResult.builder()
                    .success(success)
                    .transactionId(UUID.randomUUID().toString())
                    .errorMessage(success ? null : "Payment processing failed")
                    .processedAt(LocalDateTime.now())
                    .build();

        } catch (Exception e) {
            log.error("Payment gateway error: paymentId={}", request.getPaymentId(), e);
            return PaymentGatewayResult.builder()
                    .success(false)
                    .errorMessage("결제 처리 중 오류 발생: " + e.getMessage())
                    .processedAt(LocalDateTime.now())
                    .build();
        }
    }

    /**
     * Phase 3: 결제 결과 처리 (트랜잭션)
     */
    @Transactional(timeout = 10)
    private PaymentResponse finalizePayment(PaymentRequest request,
                                            ReservationValidationResult validation,
                                            PaymentGatewayResult gatewayResult) {
        try {
            if (gatewayResult.isSuccess()) {
                return handlePaymentSuccess(request, validation, gatewayResult);
            } else {
                return handlePaymentFailure(request, validation, gatewayResult);
            }

        } catch (Exception e) {
            log.error("Error finalizing payment: paymentId={}", request.getPaymentId(), e);

            // Critical Alert
            if (gatewayResult.isSuccess()) {
                publishCriticalAlert("PAYMENT_SUCCESS_FINALIZATION_FAILED",
                        request.getPaymentId(), e.getMessage());
            }

            throw e;
        }
    }

    /**
     * 결제 성공 처리 - 주문 생성 및 재고 확정
     */
    private PaymentResponse handlePaymentSuccess(PaymentRequest request,
                                                 ReservationValidationResult validation,
                                                 PaymentGatewayResult gatewayResult) {
        try {
            ReservationState reservation = validation.getReservation();

            // 1. 주문 생성 (결제 완료 후!)
            String orderId = orderService.createConfirmedOrder(CreateOrderRequest.builder()
                    .customerId(reservation.getCustomerId())
                    .productId(reservation.getProductId())
                    .quantity(reservation.getQuantity())
                    .amount(request.getAmount())
                    .currency(request.getCurrency())
                    .paymentId(request.getPaymentId())
                    .reservationId(request.getReservationId())
                    .build());

            // 2. 재고 확정
            boolean inventoryConfirmed = inventoryService.confirmReservation(request.getReservationId());
            if (!inventoryConfirmed) {
                log.error("CRITICAL: Payment succeeded but inventory confirmation failed: " +
                        "orderId={}, reservationId={}", orderId, request.getReservationId());
                publishCriticalAlert("ORDER_CREATED_INVENTORY_CONFIRMATION_FAILED",
                        orderId, request.getReservationId());
            }

            // 3. 성공 응답
            PaymentResponse response = PaymentResponse.completed(
                    request.getPaymentId(),
                    orderId,
                    request.getReservationId(),
                    request.getAmount(),
                    request.getCurrency()
            );

            // 4. 완료 상태 캐싱
            cacheCompletedPayment(response);

            // 5. 예약 정보 정리
            cleanupReservationData(request.getReservationId());

            return response;

        } catch (Exception e) {
            log.error("Error handling payment success: paymentId={}", request.getPaymentId(), e);
            throw e;
        }
    }

    /**
     * 결제 실패 처리 - 예약 해제
     */
    private PaymentResponse handlePaymentFailure(PaymentRequest request,
                                                 ReservationValidationResult validation,
                                                 PaymentGatewayResult gatewayResult) {
        try {
            // 재고 예약 해제
            boolean cancelled = inventoryService.cancelReservation(request.getReservationId());
            if (!cancelled) {
                log.warn("Failed to cancel inventory reservation: reservationId={}",
                        request.getReservationId());
            }

            // 실패 응답
            PaymentResponse response = PaymentResponse.failed(
                    request.getPaymentId(),
                    request.getReservationId(),
                    request.getAmount(),
                    request.getCurrency(),
                    "PAYMENT_FAILED",
                    "결제에 실패했습니다: " + gatewayResult.getErrorMessage()
            );

            // 예약 정보 정리
            cleanupReservationData(request.getReservationId());

            return response;

        } catch (Exception e) {
            log.error("Error handling payment failure: paymentId={}", request.getPaymentId(), e);
            throw e;
        }
    }

    /**
     * 결제 상태 조회 (고성능 캐시 기반)
     */
    public PaymentResponse getPaymentStatus(String paymentId) {
        try {
            String cacheKey = "payment:" + paymentId;
            Object paymentData = cacheService.getCachedData(cacheKey);

            if (paymentData instanceof PaymentResponse) {
                return (PaymentResponse) paymentData;
            } else if (paymentData != null) {
                return objectMapper.convertValue(paymentData, PaymentResponse.class);
            }

            return null;

        } catch (Exception e) {
            log.error("Error getting payment status: paymentId={}", paymentId, e);
            return null;
        }
    }

    // ========== 헬퍼 메서드들 ==========

    private boolean processExternalPayment(PaymentRequest request) throws Exception {
        // 한정 상품 최적화 (빠른 처리, 높은 성공율)
        if (Math.random() < 0.02) { // 2% 실패율
            throw new Exception("Payment gateway error");
        }

        Thread.sleep((long) (50 + Math.random() * 150)); // 50-200ms
        return Math.random() >= 0.03; // 97% 성공율
    }

    private void cleanupExpiredReservation(String reservationId, ReservationState reservation) {
        try {
            inventoryService.cancelReservation(reservationId);
            cleanupReservationData(reservationId);
            log.info("Expired reservation cleaned up: reservationId={}", reservationId);

        } catch (Exception e) {
            log.error("Error cleaning up expired reservation: reservationId={}", reservationId, e);
        }
    }

    private void cleanupReservationData(String reservationId) {
        try {
            String cacheKey = "reservation:" + reservationId;
            cacheService.deleteCache(cacheKey);

        } catch (Exception e) {
            log.error("Error cleaning up reservation data: reservationId={}", reservationId, e);
        }
    }

    private void cacheCompletedPayment(PaymentResponse response) {
        try {
            String cacheKey = "payment:" + response.getPaymentId();
            cacheService.cacheData(cacheKey, response, 86400); // 24시간

        } catch (Exception e) {
            log.error("Error caching completed payment: paymentId={}", response.getPaymentId(), e);
        }
    }

    private void compensateFailedPayment(PaymentRequest request) {
        try {
            if (request.getReservationId() != null) {
                inventoryService.cancelReservation(request.getReservationId());
                cleanupReservationData(request.getReservationId());
                log.info("Failed payment compensated: paymentId={}", request.getPaymentId());
            }

        } catch (Exception e) {
            log.error("Error compensating failed payment: paymentId={}", request.getPaymentId(), e);
        }
    }

    private void publishPaymentEvents(PaymentResponse response, ReservationState reservation) {
        try {
            if ("COMPLETED".equals(response.getStatus())) {
                eventService.publishPaymentCompleted(response);
            } else {
                eventService.publishPaymentFailed(response);
            }

        } catch (Exception e) {
            log.error("Error publishing payment events: paymentId={}", response.getPaymentId(), e);
        }
    }

    private void publishCriticalAlert(String alertType, String identifier, String details) {
        try {
            Map<String, Object> alert = new HashMap<>();
            alert.put("alertType", alertType);
            alert.put("identifier", identifier);
            alert.put("details", details);
            alert.put("timestamp", System.currentTimeMillis());
            alert.put("severity", "CRITICAL");

            String alertJson = objectMapper.writeValueAsString(alert);
            kafkaTemplate.send("critical-alerts", alertType, alertJson);

            log.error("CRITICAL ALERT: {} - {}", alertType, identifier);

        } catch (Exception e) {
            log.error("Error publishing critical alert: {}", e.getMessage(), e);
        }
    }

    private PaymentResponse createFailureResponse(PaymentRequest request, String errorMessage) {
        return PaymentResponse.failed(
                request.getPaymentId(),
                request.getReservationId(),
                request.getAmount(),
                request.getCurrency(),
                "REJECTED",
                errorMessage
        );
    }

    private PaymentResponse createErrorResponse(PaymentRequest request, Exception e) {
        return PaymentResponse.failed(
                request.getPaymentId(),
                request.getReservationId(),
                request.getAmount(),
                request.getCurrency(),
                "ERROR",
                "처리 중 오류 발생: " + e.getMessage()
        );
    }
}
/**
 * ========================================
 * PaymentProcessingService (결제 처리 전담)
 * ========================================
 */
package com.example.payment.application.service;

import com.example.payment.application.dto.PaymentGatewayRequest;
import com.example.payment.application.dto.PaymentGatewayResult;
import com.example.payment.application.event.publisher.PaymentEventService;
import com.example.payment.domain.exception.PaymentException;
import com.example.payment.domain.model.common.Money;
import com.example.payment.domain.model.payment.Payment;
import com.example.payment.domain.model.payment.PaymentMethod;
import com.example.payment.domain.model.payment.PaymentStatus;
import com.example.payment.domain.model.reservation.InventoryReservation;
import com.example.payment.domain.service.PaymentGatewayService;
import com.example.payment.infrastructure.gateway.PaymentGatewayFactory;
import com.example.payment.infrastructure.persistance.redis.repository.CacheService;
import com.example.payment.infrastructure.util.IdGenerator;
import com.example.payment.presentation.dto.request.PaymentRequest;
import com.example.payment.presentation.dto.response.PaymentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentProcessingService {

    // 의존성들
    private final PaymentGatewayFactory gatewayFactory; // 팩토리를 통해 게이트웨이 선택
    private final CacheService cacheService;
    private final PaymentEventService paymentEventService;
    private final ReservationService reservationService; // 예약 검증용

    /**
     * 예약 기반 결제 처리 (메인 API)
     */
    public PaymentResponse processReservationPayment(PaymentRequest request) {
        log.info("Processing reservation payment: paymentId={}, reservationId={}, amount={}",
                request.getPaymentId(), request.getReservationId(), request.getAmount());

        try {
            // 1. 예약 유효성 검증
            if (!validateReservation(request.getReservationId(), request.getCustomerId())) {
                return PaymentResponse.failed(
                        request.getPaymentId(),
                        request.getReservationId(),
                        request.getAmount(),
                        request.getCurrency(),
                        "INVALID_RESERVATION",
                        "예약이 유효하지 않거나 만료되었습니다."
                );
            }

            // 2. 결제 처리
            Payment payment = processPayment(
                    request.getPaymentId(),
                    null, // orderId는 결제 성공 후 생성
                    request.getReservationId(),
                    request.getCustomerId(),
                    request.getAmount(),
                    request.getCurrency(),
                    request.getPaymentMethod()
            );

            // 3. 결과에 따른 응답 생성
            PaymentResponse response;
            if (payment.isCompleted()) {
                // 주문 ID 생성 (결제 성공 시)
                String orderId = IdGenerator.generateOrderId();
                payment.setOrderId(orderId);

                // 캐시 업데이트
                String cacheKey = "payment:" + payment.getPaymentId();
                cacheService.cacheData(cacheKey, payment, 86400);

                response = PaymentResponse.success(
                        payment.getPaymentId(),
                        orderId,
                        payment.getReservationId(),
                        payment.getAmount().getAmount(),
                        payment.getAmount().getCurrency(),
                        payment.getTransactionId(),
                        generateApprovalNumber()
                );

                // 성공 이벤트 발행
                paymentEventService.publishPaymentProcessed(response);

            } else {
                response = PaymentResponse.failed(
                        payment.getPaymentId(),
                        payment.getReservationId(),
                        payment.getAmount().getAmount(),
                        payment.getAmount().getCurrency(),
                        "PAYMENT_FAILED",
                        payment.getFailureReason() != null ? payment.getFailureReason() : "결제 처리 실패"
                );

                // 실패 이벤트 발행
                paymentEventService.publishPaymentFailed(response);
            }

            return response;

        } catch (Exception e) {
            log.error("Error processing reservation payment: paymentId={}, reservationId={}",
                    request.getPaymentId(), request.getReservationId(), e);

            PaymentResponse errorResponse = PaymentResponse.failed(
                    request.getPaymentId(),
                    request.getReservationId(),
                    request.getAmount(),
                    request.getCurrency(),
                    "SYSTEM_ERROR",
                    "시스템 오류가 발생했습니다: " + e.getMessage()
            );

            // 에러 이벤트 발행
            paymentEventService.publishPaymentFailed(errorResponse);

            return errorResponse;
        }
    }

    /**
     * 결제 처리 - 도메인 객체 반환 (내부 메서드)
     */
    public Payment processPayment(String paymentId, String orderId, String reservationId,
                                  String customerId, BigDecimal amount, String currency, String method) {

        log.info("Processing payment: paymentId={}, orderId={}, amount={}", paymentId, orderId, amount);

        try {
            // 결제 전 상태로 Payment 객체 생성
            Payment payment = Payment.builder()
                    .paymentId(paymentId)
                    .orderId(orderId)
                    .reservationId(reservationId)
                    .customerId(customerId)
                    .amount(Money.of(amount, currency))
                    .method(PaymentMethod.valueOf(method.toUpperCase().replace("_", "_"))) // CREDIT_CARD 등
                    .status(PaymentStatus.PROCESSING)
                    .build();

            // 캐시에 처리중 상태 저장
            String cacheKey = "payment:" + paymentId;
            cacheService.cacheData(cacheKey, payment, 3600); // 1시간

            // 외부 PG 게이트웨이 선택 및 결제 요청
            PaymentGatewayService gateway = gatewayFactory.getGateway(method);

            PaymentGatewayRequest pgRequest = PaymentGatewayRequest.builder()
                    .paymentId(paymentId)
                    .customerId(customerId)
                    .amount(amount)
                    .currency(currency)
                    .method(method)
                    .orderName("온라인 상품 결제")
                    .build();

            PaymentGatewayResult pgResult = gateway.processPayment(pgRequest);

            // 결제 결과에 따른 상태 업데이트
            if (pgResult.isSuccess()) {
                payment.markAsCompleted(pgResult.getTransactionId());
                log.info("Payment completed successfully: paymentId={}, transactionId={}",
                        paymentId, pgResult.getTransactionId());
            } else {
                payment.markAsFailed(pgResult.getErrorMessage());
                log.warn("Payment failed: paymentId={}, reason={}", paymentId, pgResult.getErrorMessage());
            }

            // 최종 상태를 캐시에 저장
            cacheService.cacheData(cacheKey, payment, 86400); // 24시간

            return payment;

        } catch (Exception e) {
            log.error("Error processing payment: paymentId={}", paymentId, e);

            // 에러 상태의 Payment 객체 반환
            Payment failedPayment = Payment.builder()
                    .paymentId(paymentId)
                    .orderId(orderId)
                    .reservationId(reservationId)
                    .customerId(customerId)
                    .amount(Money.of(amount, currency))
                    .method(PaymentMethod.valueOf(method.toUpperCase().replace("_", "_")))
                    .status(PaymentStatus.FAILED)
                    .failureReason("시스템 오류: " + e.getMessage())
                    .processedAt(LocalDateTime.now())
                    .build();

            // 실패 상태를 캐시에 저장
            String cacheKey = "payment:" + paymentId;
            cacheService.cacheData(cacheKey, failedPayment, 86400);

            return failedPayment;
        }
    }

    /**
     * 결제 환불
     */
    public boolean refundPayment(String paymentId) {
        try {
            log.info("Refunding payment: paymentId={}", paymentId);

            // 결제 정보 조회
            Payment payment = getPayment(paymentId);
            if (payment == null) {
                log.warn("Payment not found for refund: paymentId={}", paymentId);
                return false;
            }

            // 환불 가능 여부 확인
            if (!payment.canBeRefunded()) {
                log.warn("Payment cannot be refunded: paymentId={}, status={}",
                        paymentId, payment.getStatus());
                return false;
            }

            // 외부 PG 게이트웨이를 통한 환불 처리
            PaymentGatewayService gateway = gatewayFactory.getGateway(payment.getMethod().name());
            boolean refunded = gateway.refundPayment(payment.getTransactionId());

            if (refunded) {
                // 환불 상태로 업데이트
                payment.setStatus(PaymentStatus.REFUNDED);
                payment.setProcessedAt(LocalDateTime.now());

                // 캐시 업데이트
                String cacheKey = "payment:" + paymentId;
                cacheService.cacheData(cacheKey, payment, 86400);

                log.info("Payment refunded successfully: paymentId={}", paymentId);
                return true;
            } else {
                log.warn("PG refund failed: paymentId={}", paymentId);
                return false;
            }

        } catch (Exception e) {
            log.error("Error refunding payment: paymentId={}", paymentId, e);
            return false;
        }
    }

    /**
     * 결제 상태 조회
     */
    public PaymentResponse getPaymentStatus(String paymentId) {
        try {
            log.debug("Getting payment status: paymentId={}", paymentId);

            Payment payment = getPayment(paymentId);
            if (payment == null) {
                log.warn("Payment not found: paymentId={}", paymentId);
                return null;
            }

            return PaymentResponse.builder()
                    .paymentId(payment.getPaymentId())
                    .orderId(payment.getOrderId())
                    .reservationId(payment.getReservationId())
                    .amount(payment.getAmount().getAmount())
                    .currency(payment.getAmount().getCurrency())
                    .status(payment.getStatus().name())
                    .transactionId(payment.getTransactionId())
                    .approvalNumber(payment.getApprovalNumber())
                    .message(getStatusMessage(payment.getStatus()))
                    .createdAt(payment.getCreatedAt())
                    .updatedAt(payment.getUpdatedAt())
                    .build();

        } catch (Exception e) {
            log.error("Error getting payment status: paymentId={}", paymentId, e);
            return PaymentResponse.failed(paymentId, null, BigDecimal.ZERO, "KRW",
                    "SYSTEM_ERROR", "상태 조회 중 오류 발생");
        }
    }

    /**
     * Payment 도메인 객체 조회
     */
    public Payment getPayment(String paymentId) {
        try {
            String cacheKey = "payment:" + paymentId;
            Object cachedData = cacheService.getCachedData(cacheKey);

            if (cachedData != null) {
                return (Payment) cachedData;
            }

            log.debug("Payment not found in cache: paymentId={}", paymentId);
            return null;

        } catch (Exception e) {
            log.error("Error getting payment: paymentId={}", paymentId, e);
            return null;
        }
    }

    /**
     * 예약 유효성 검증
     */
    private boolean validateReservation(String reservationId, String customerId) {
        try {
            // ReservationService를 통해 예약 유효성 확인
            InventoryReservation reservation = reservationService.getReservation(reservationId);

            if (reservation == null) {
                log.warn("Reservation not found: reservationId={}", reservationId);
                return false;
            }

            // 고객 ID 일치 여부 확인
            if (!customerId.equals(reservation.getCustomerId())) {
                log.warn("Customer ID mismatch: reservationId={}, expected={}, actual={}",
                        reservationId, reservation.getCustomerId(), customerId);
                return false;
            }

            // 예약 상태 및 만료 시간 확인
            if (!reservation.canBeConfirmed()) {
                log.warn("Reservation cannot be confirmed: reservationId={}, status={}, expired={}",
                        reservationId, reservation.getStatus(), reservation.isExpired());
                return false;
            }

            return true;

        } catch (Exception e) {
            log.error("Error validating reservation: reservationId={}", reservationId, e);
            return false;
        }
    }

    /**
     * 결제 상태별 메시지 생성
     */
    private String getStatusMessage(PaymentStatus status) {
        switch (status) {
            case PROCESSING:
                return "결제 처리 중입니다.";
            case COMPLETED:
                return "결제가 완료되었습니다.";
            case FAILED:
                return "결제가 실패했습니다.";
            case REFUNDED:
                return "결제가 환불되었습니다.";
            case CANCELLED:
                return "결제가 취소되었습니다.";
            default:
                return "알 수 없는 상태입니다.";
        }
    }

    /**
     * 승인번호 생성 (임시)
     */
    private String generateApprovalNumber() {
        return "APPR-" + System.currentTimeMillis();
    }

    /**
     * 결제 게이트웨이 헬스체크
     */
    public boolean isPaymentGatewayHealthy() {
        try {
            return gatewayFactory.areAllGatewaysHealthy();
        } catch (Exception e) {
            log.error("Error checking payment gateway health", e);
            return false;
        }
    }

    /**
     * 결제 재시도 (실패한 결제에 대해)
     */
    public PaymentResponse retryPayment(String originalPaymentId, PaymentRequest newRequest) {
        log.info("Retrying payment: originalPaymentId={}, newPaymentId={}",
                originalPaymentId, newRequest.getPaymentId());

        try {
            // 원본 결제 정보 조회
            Payment originalPayment = getPayment(originalPaymentId);
            if (originalPayment != null && originalPayment.isCompleted()) {
                log.warn("Original payment already completed, cannot retry: paymentId={}", originalPaymentId);
                return PaymentResponse.failed(
                        newRequest.getPaymentId(),
                        newRequest.getReservationId(),
                        newRequest.getAmount(),
                        newRequest.getCurrency(),
                        "ALREADY_COMPLETED",
                        "이미 완료된 결제는 재시도할 수 없습니다."
                );
            }

            // 새로운 결제로 처리 (기존과 동일한 로직)
            return processReservationPayment(newRequest);

        } catch (Exception e) {
            log.error("Error retrying payment: originalPaymentId={}, newPaymentId={}",
                    originalPaymentId, newRequest.getPaymentId(), e);

            return PaymentResponse.failed(
                    newRequest.getPaymentId(),
                    newRequest.getReservationId(),
                    newRequest.getAmount(),
                    newRequest.getCurrency(),
                    "RETRY_ERROR",
                    "결제 재시도 중 오류 발생: " + e.getMessage()
            );
        }
    }

    /**
     * 배치 결제 상태 업데이트 (관리자용)
     */
    public void syncPaymentStatuses() {
        log.info("Starting payment status synchronization");

        try {
            // TODO: 실제 구현에서는 DB나 캐시에서 PROCESSING 상태인 결제들을 조회하고
            // 각 PG사 API를 호출하여 실제 상태와 동기화

            log.info("Payment status synchronization completed");

        } catch (Exception e) {
            log.error("Error during payment status synchronization", e);
        }
    }

    /**
     * 결제 통계 조회 (관리자용)
     */
    public java.util.Map<String, Object> getPaymentStatistics(java.time.LocalDate from, java.time.LocalDate to) {
        log.debug("Getting payment statistics: from={}, to={}", from, to);

        try {
            // TODO: 실제 구현에서는 DB나 분석 시스템에서 통계 데이터 조회

            return java.util.Map.of(
                    "period", from + " ~ " + to,
                    "totalCount", 0,
                    "successCount", 0,
                    "failureCount", 0,
                    "totalAmount", java.math.BigDecimal.ZERO,
                    "message", "통계 조회 기능 구현 예정"
            );

        } catch (Exception e) {
            log.error("Error getting payment statistics", e);
            return java.util.Map.of(
                    "error", "통계 조회 중 오류 발생: " + e.getMessage()
            );
        }
    }
}
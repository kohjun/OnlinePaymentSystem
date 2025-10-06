package com.example.payment.application.service;

import com.example.payment.application.dto.PaymentGatewayRequest;
import com.example.payment.application.dto.PaymentGatewayResult;
import com.example.payment.domain.model.common.Money;
import com.example.payment.domain.model.payment.Payment;
import com.example.payment.domain.model.payment.PaymentMethod;
import com.example.payment.domain.model.payment.PaymentStatus;
import com.example.payment.domain.service.PaymentGatewayService;
import com.example.payment.infrastructure.gateway.PaymentGatewayFactory;
import com.example.payment.infrastructure.persistence.redis.repository.CacheService;
import com.example.payment.infrastructure.persistence.wal.WalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 결제 처리 서비스 - 단일 책임 원칙 준수
 *
 * 🎯 단일 책임: 결제(Payment) 처리만 담당
 *
 * 담당 범위:
 * - PG 게이트웨이 연동
 * - 결제 상태 관리
 * - 결제 환불
 * - 결제 조회
 *
 * 담당하지 않음:
 * - 주문 생성 → OrderService
 * - 재고 관리 → ReservationService, InventoryManagementService
 * - WAL 로그 → WalService (횡단 관심사)
 * - 이벤트 발행 → PaymentEventService (Application Layer)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentProcessingService {

    // 인프라 서비스들
    private final PaymentGatewayFactory gatewayFactory;
    private final CacheService cacheService;
    private final WalService walService;

    // 캐시 TTL
    private static final int PAYMENT_CACHE_TTL_SECONDS = 86400; // 24시간

    /**
     * 결제 처리 (PG 연동)
     *
     * @param paymentId 결제 ID
     * @param orderId 주문 ID
     * @param reservationId 예약 ID
     * @param customerId 고객 ID
     * @param amount 금액
     * @param currency 통화
     * @param method 결제 수단
     * @return 결제 도메인 객체
     */
    public Payment processPayment(String paymentId, String orderId, String reservationId,
                                  String customerId, BigDecimal amount, String currency, String method) {

        log.info("Processing payment: paymentId={}, orderId={}, amount={}, method={}",
                paymentId, orderId, amount, method);

        try {
            // 1. WAL 시작 로그
            String walLogId = walService.logOperationStart(
                    "PAYMENT_PROCESS_START",
                    "payments",
                    buildPaymentJson(paymentId, orderId, customerId, amount, currency, "PROCESSING")
            );

            // 2. Payment 도메인 객체 생성 (처리 중 상태)
            Payment payment = Payment.builder()
                    .paymentId(paymentId)
                    .orderId(orderId)
                    .reservationId(reservationId)
                    .customerId(customerId)
                    .amount(Money.of(amount, currency))
                    .method(PaymentMethod.valueOf(method.toUpperCase().replace("_", "_")))
                    .status(PaymentStatus.PROCESSING)
                    .build();

            // 3. 캐시에 처리 중 상태 저장
            String cacheKey = "payment:" + paymentId;
            cacheService.cacheData(cacheKey, payment, PAYMENT_CACHE_TTL_SECONDS);

            // 4. PG 게이트웨이 선택 및 결제 요청
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

            // 5. PG 결과에 따른 상태 업데이트
            if (pgResult.isSuccess()) {
                payment.markAsCompleted(pgResult.getTransactionId());
                payment.setApprovalNumber(pgResult.getApprovalNumber());
                payment.setGatewayName(pgResult.getGatewayName());

                log.info("Payment completed: paymentId={}, transactionId={}",
                        paymentId, pgResult.getTransactionId());

                // WAL 완료 로그
                walService.logOperationComplete(
                        "PAYMENT_PROCESS_COMPLETE",
                        "payments",
                        buildPaymentJson(paymentId, orderId, customerId, amount, currency, "PROCESSING"),
                        buildPaymentJson(paymentId, orderId, customerId, amount, currency, "COMPLETED")
                );
                walService.updateLogStatus(walLogId, "COMMITTED", "결제 완료");

            } else {
                payment.markAsFailed(pgResult.getErrorMessage());

                log.warn("Payment failed: paymentId={}, reason={}", paymentId, pgResult.getErrorMessage());

                // WAL 실패 로그
                walService.updateLogStatus(walLogId, "FAILED", "결제 실패: " + pgResult.getErrorMessage());
            }

            // 6. 최종 상태를 캐시에 저장
            cacheService.cacheData(cacheKey, payment, PAYMENT_CACHE_TTL_SECONDS);

            return payment;

        } catch (Exception e) {
            log.error("Error processing payment: paymentId={}", paymentId, e);

            // WAL 에러 로그
            walService.logOperationFailure(
                    "PAYMENT_PROCESS_ERROR",
                    "payments",
                    e.getMessage()
            );

            // 실패 상태의 Payment 객체 반환
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
            cacheService.cacheData(cacheKey, failedPayment, PAYMENT_CACHE_TTL_SECONDS);

            return failedPayment;
        }
    }

    /**
     * 결제 환불
     *
     * @param paymentId 결제 ID
     * @return 환불 성공 여부
     */
    public boolean refundPayment(String paymentId) {
        try {
            log.info("Refunding payment: paymentId={}", paymentId);

            // 1. 결제 정보 조회
            Payment payment = getPayment(paymentId);
            if (payment == null) {
                log.warn("Payment not found for refund: paymentId={}", paymentId);
                return false;
            }

            // 2. 환불 가능 여부 확인
            if (!payment.canBeRefunded()) {
                log.warn("Payment cannot be refunded: paymentId={}, status={}",
                        paymentId, payment.getStatus());
                return false;
            }

            // 3. WAL 로그
            String walLogId = walService.logOperationStart(
                    "PAYMENT_REFUND_START",
                    "payments",
                    buildRefundJson(paymentId, payment.getStatus().name())
            );

            // 4. PG 게이트웨이를 통한 환불 처리
            PaymentGatewayService gateway = gatewayFactory.getGateway(payment.getMethod().name());
            boolean refunded = gateway.refundPayment(payment.getTransactionId());

            if (refunded) {
                // 5. 환불 상태로 업데이트
                payment.markAsRefunded();

                // 6. 캐시 업데이트
                String cacheKey = "payment:" + paymentId;
                cacheService.cacheData(cacheKey, payment, PAYMENT_CACHE_TTL_SECONDS);

                // 7. WAL 완료
                walService.logOperationComplete(
                        "PAYMENT_REFUND_COMPLETE",
                        "payments",
                        buildRefundJson(paymentId, "COMPLETED"),
                        buildRefundJson(paymentId, "REFUNDED")
                );
                walService.updateLogStatus(walLogId, "COMMITTED", "환불 완료");

                log.info("Payment refunded successfully: paymentId={}", paymentId);
                return true;

            } else {
                walService.updateLogStatus(walLogId, "FAILED", "PG 환불 실패");
                log.warn("PG refund failed: paymentId={}", paymentId);
                return false;
            }

        } catch (Exception e) {
            log.error("Error refunding payment: paymentId={}", paymentId, e);

            walService.logOperationFailure(
                    "PAYMENT_REFUND_ERROR",
                    "payments",
                    e.getMessage()
            );

            return false;
        }
    }

    /**
     * 결제 조회
     *
     * @param paymentId 결제 ID
     * @return 결제 도메인 객체 (없으면 null)
     */
    public Payment getPayment(String paymentId) {
        try {
            String cacheKey = "payment:" + paymentId;
            Object cachedData = cacheService.getCachedData(cacheKey);

            if (cachedData != null) {
                log.debug("Payment found in cache: paymentId={}", paymentId);
                return (Payment) cachedData;
            }

            log.debug("Payment not found: paymentId={}", paymentId);
            return null;

        } catch (Exception e) {
            log.error("Error getting payment: paymentId={}", paymentId, e);
            return null;
        }
    }

    /**
     * 결제 재시도
     *
     * @param originalPaymentId 원본 결제 ID
     * @param newPaymentId 새 결제 ID
     * @param orderId 주문 ID
     * @param reservationId 예약 ID
     * @param customerId 고객 ID
     * @param amount 금액
     * @param currency 통화
     * @param method 결제 수단
     * @return 재시도 결제 도메인 객체
     */
    public Payment retryPayment(String originalPaymentId, String newPaymentId, String orderId,
                                String reservationId, String customerId, BigDecimal amount,
                                String currency, String method) {

        log.info("Retrying payment: originalPaymentId={}, newPaymentId={}",
                originalPaymentId, newPaymentId);

        // 원본 결제 정보 조회
        Payment originalPayment = getPayment(originalPaymentId);
        if (originalPayment != null && originalPayment.isCompleted()) {
            log.warn("Original payment already completed, cannot retry: paymentId={}", originalPaymentId);
            return originalPayment;
        }

        // 새로운 결제 처리
        return processPayment(newPaymentId, orderId, reservationId, customerId, amount, currency, method);
    }

    /**
     * 결제 게이트웨이 헬스체크
     *
     * @return 모든 게이트웨이 정상 여부
     */
    public boolean isPaymentGatewayHealthy() {
        try {
            return gatewayFactory.areAllGatewaysHealthy();
        } catch (Exception e) {
            log.error("Error checking payment gateway health", e);
            return false;
        }
    }

    // ========================================
    // 내부 헬퍼 메서드
    // ========================================

    /**
     * 결제 JSON 생성
     */
    private String buildPaymentJson(String paymentId, String orderId, String customerId,
                                    BigDecimal amount, String currency, String status) {
        return String.format(
                "{\"paymentId\":\"%s\",\"orderId\":\"%s\",\"customerId\":\"%s\",\"amount\":%s,\"currency\":\"%s\",\"status\":\"%s\",\"timestamp\":\"%s\"}",
                paymentId, orderId, customerId, amount, currency, status, LocalDateTime.now()
        );
    }

    /**
     * 환불 JSON 생성
     */
    private String buildRefundJson(String paymentId, String status) {
        return String.format(
                "{\"paymentId\":\"%s\",\"status\":\"%s\",\"timestamp\":\"%s\"}",
                paymentId, status, LocalDateTime.now()
        );
    }
}
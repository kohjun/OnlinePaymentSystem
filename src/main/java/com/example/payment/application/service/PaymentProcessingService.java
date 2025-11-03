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
import com.example.payment.infrastructure.util.IdGenerator;
import com.example.payment.presentation.dto.request.PaymentProcessRequest;
import com.example.payment.presentation.dto.response.PaymentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * ✅ 결제 처리 서비스
 * [수정] getPayment의 캐시 읽기 방식을 getCachedObject로 변경
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentProcessingService {

    private final PaymentGatewayFactory gatewayFactory;
    private final CacheService cacheService;
    private final WalService walService;

    private static final int PAYMENT_CACHE_TTL_SECONDS = 86400; // 24시간

    /**
     * 결제 처리 (내부 메서드)
     */
    public Payment processPayment(
            String transactionId,
            String paymentId,
            String orderId,
            String reservationId,
            String customerId,
            BigDecimal amount,
            String currency,
            String method) {

        log.info("Processing payment: txId={}, paymentId={}, orderId={}, amount={}, method={}",
                transactionId, paymentId, orderId, amount, method);

        try {
            String entityIds = buildEntityIdsJson(paymentId, orderId, reservationId);
            String afterData = buildPaymentJson(paymentId, orderId, customerId, amount, currency, "PROCESSING");

            String walLogId = walService.logOperationStart(
                    transactionId,
                    "PAYMENT_PROCESS_START",
                    "payments",
                    entityIds,
                    afterData
            );

            Payment payment = Payment.builder()
                    .paymentId(paymentId)
                    .orderId(orderId)
                    .reservationId(reservationId)
                    .customerId(customerId)
                    .amount(Money.of(amount, currency))
                    .method(PaymentMethod.valueOf(method.toUpperCase().replace("_", "_")))
                    .status(PaymentStatus.PROCESSING)
                    .build();

            String cacheKey = "payment:" + paymentId;
            // cacheData는 cacheObject의 alias이며 String(JSON)으로 저장
            cacheService.cacheData(cacheKey, payment, PAYMENT_CACHE_TTL_SECONDS);

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

            if (pgResult.isSuccess()) {
                payment.markAsCompleted(pgResult.getTransactionId());
                payment.setApprovalNumber(pgResult.getApprovalNumber());
                payment.setGatewayName(pgResult.getGatewayName());

                log.info("Payment completed: paymentId={}, transactionId={}",
                        paymentId, pgResult.getTransactionId());

                String beforeData = buildPaymentJson(paymentId, orderId, customerId, amount, currency, "PROCESSING");
                String completedData = buildPaymentJson(paymentId, orderId, customerId, amount, currency, "COMPLETED");

                walService.logOperationComplete(
                        transactionId,
                        "PAYMENT_PROCESS_COMPLETE",
                        "payments",
                        entityIds,
                        beforeData,
                        completedData
                );
                walService.updateLogStatus(walLogId, "COMMITTED", "결제 완료");

            } else {
                payment.markAsFailed(pgResult.getErrorMessage());
                log.warn("Payment failed: paymentId={}, reason={}", paymentId, pgResult.getErrorMessage());
                walService.updateLogStatus(walLogId, "FAILED", "결제 실패: " + pgResult.getErrorMessage());
            }

            cacheService.cacheData(cacheKey, payment, PAYMENT_CACHE_TTL_SECONDS);
            return payment;

        } catch (Exception e) {
            log.error("Error processing payment: paymentId={}", paymentId, e);

            String entityIds = buildEntityIdsJson(paymentId, orderId, reservationId);
            walService.logOperationFailure(
                    transactionId,
                    "PAYMENT_PROCESS_ERROR",
                    "payments",
                    entityIds,
                    e.getMessage()
            );

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

            String cacheKey = "payment:" + paymentId;
            cacheService.cacheData(cacheKey, failedPayment, PAYMENT_CACHE_TTL_SECONDS);

            return failedPayment;
        }
    }

    /**
     * 결제 환불
     */
    public boolean refundPayment(String paymentId) {
        try {
            String transactionId = IdGenerator.generateCorrelationId();
            log.info("Refunding payment: txId={}, paymentId={}", transactionId, paymentId);

            Payment payment = getPayment(paymentId);
            if (payment == null) {
                log.warn("Payment not found for refund: paymentId={}", paymentId);
                return false;
            }

            if (!payment.canBeRefunded()) {
                log.warn("Payment cannot be refunded: paymentId={}, status={}",
                        paymentId, payment.getStatus());
                return false;
            }

            String entityIds = buildEntityIdsJson(paymentId, payment.getOrderId(), payment.getReservationId());
            String afterData = buildRefundJson(paymentId, payment.getStatus().name());

            String walLogId = walService.logOperationStart(
                    transactionId,
                    "PAYMENT_REFUND_START",
                    "payments",
                    entityIds,
                    afterData
            );

            PaymentGatewayService gateway = gatewayFactory.getGateway(payment.getMethod().name());
            boolean refunded = gateway.refundPayment(payment.getTransactionId());

            if (refunded) {
                payment.markAsRefunded();

                String cacheKey = "payment:" + paymentId;
                cacheService.cacheData(cacheKey, payment, PAYMENT_CACHE_TTL_SECONDS);

                String beforeData = buildRefundJson(paymentId, "COMPLETED");
                String refundedData = buildRefundJson(paymentId, "REFUNDED");

                walService.logOperationComplete(
                        transactionId,
                        "PAYMENT_REFUND_COMPLETE",
                        "payments",
                        entityIds,
                        beforeData,
                        refundedData
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
            return false;
        }
    }

    /**
     * 예약 기반 결제 처리 (PaymentProcessRequest 받음)
     */
    public PaymentResponse processReservationPayment(PaymentProcessRequest request) {
        try {
            String transactionId = IdGenerator.generateCorrelationId();

            Payment payment = processPayment(
                    transactionId,
                    request.getPaymentId(),
                    request.getOrderId(),
                    request.getReservationId(),
                    request.getCustomerId(),
                    request.getAmount(),
                    request.getCurrency(),
                    request.getPaymentMethod()
            );

            return convertToResponse(payment);

        } catch (Exception e) {
            log.error("Error processing reservation payment: paymentId={}",
                    request.getPaymentId(), e);

            return PaymentResponse.failed(
                    request.getPaymentId(),
                    request.getReservationId(),
                    request.getAmount(),
                    request.getCurrency(),
                    "SYSTEM_ERROR",
                    "시스템 오류: " + e.getMessage()
            );
        }
    }

    /**
     * 결제 상태 조회 (Response DTO 반환)
     */
    public PaymentResponse getPaymentStatus(String paymentId) {
        try {
            Payment payment = getPayment(paymentId);
            if (payment == null) {
                return null;
            }
            return convertToResponse(payment);
        } catch (Exception e) {
            log.error("Error getting payment status: paymentId={}", paymentId, e);
            return null;
        }
    }

    /**
     * 결제 조회
     * [수정] getCachedData (Hash 읽기) -> getCachedObject (String 읽기) 변경
     */
    public Payment getPayment(String paymentId) {
        try {
            String cacheKey = "payment:" + paymentId;
            // [수정] String(JSON)으로 저장된 객체를 읽어옵니다.
            Payment cachedData = cacheService.getCachedObject(cacheKey, Payment.class);

            if (cachedData != null) {
                log.debug("Payment found in cache: paymentId={}", paymentId);
                return cachedData;
            }

            log.debug("Payment not found: paymentId={}", paymentId);
            return null;

        } catch (Exception e) {
            log.error("Error getting payment: paymentId={}", paymentId, e);
            return null;
        }
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

    // ===================================
    // Helper Methods
    // ===================================

    private String buildEntityIdsJson(String paymentId, String orderId, String reservationId) {
        return String.format(
                "{\"paymentId\":\"%s\",\"orderId\":\"%s\",\"reservationId\":\"%s\"}",
                paymentId != null ? paymentId : "null",
                orderId != null ? orderId : "null",
                reservationId != null ? reservationId : "null"
        );
    }

    private String buildPaymentJson(String paymentId, String orderId, String customerId,
                                    BigDecimal amount, String currency, String status) {
        return String.format(
                "{\"paymentId\":\"%s\",\"orderId\":\"%s\",\"customerId\":\"%s\"," +
                        "\"amount\":%s,\"currency\":\"%s\",\"status\":\"%s\",\"timestamp\":\"%s\"}",
                paymentId, orderId, customerId, amount, currency, status, LocalDateTime.now()
        );
    }

    private String buildRefundJson(String paymentId, String status) {
        return String.format(
                "{\"paymentId\":\"%s\",\"status\":\"%s\",\"timestamp\":\"%s\"}",
                paymentId, status, LocalDateTime.now()
        );
    }

    private PaymentResponse convertToResponse(Payment payment) {
        return PaymentResponse.builder()
                .paymentId(payment.getPaymentId())
                .reservationId(payment.getReservationId())
                .orderId(payment.getOrderId())
                .amount(payment.getAmount().getAmount())
                .currency(payment.getAmount().getCurrency())
                .status(payment.getStatus().name())
                .transactionId(payment.getTransactionId())
                .approvalNumber(payment.getApprovalNumber())
                .gatewayName(payment.getGatewayName())
                .currency(payment.getFailureReason()) // [버그 의심]
                .processedAt(payment.getProcessedAt())
                .build();
    }
}
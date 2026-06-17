package com.example.payment.application.service;

import com.example.payment.application.dto.PaymentGatewayRequest;
import com.example.payment.application.dto.PaymentGatewayResult;
import com.example.payment.domain.entity.PaymentRecord;
import com.example.payment.domain.model.common.Money;
import com.example.payment.domain.model.payment.Payment;
import com.example.payment.domain.model.payment.PaymentMethod;
import com.example.payment.domain.model.payment.PaymentStatus;
import com.example.payment.domain.repository.PaymentRecordRepository;
import com.example.payment.domain.service.PaymentGatewayService;
import com.example.payment.infrastructure.gateway.PaymentGatewayFactory;
import com.example.payment.infrastructure.persistence.redis.repository.CacheService;
import com.example.payment.infrastructure.util.IdGenerator;
import com.example.payment.presentation.dto.request.PaymentProcessRequest;
import com.example.payment.presentation.dto.response.PaymentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 결제 처리 서비스 (WAL 의존성 제거)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentProcessingService {

    private final PaymentGatewayFactory gatewayFactory;
    private final CacheService cacheService;
    private final PaymentRecordRepository paymentRecordRepository;

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
            Payment payment = Payment.builder()
                    .paymentId(paymentId)
                    .orderId(orderId)
                    .reservationId(reservationId)
                    .customerId(customerId)
                    .amount(Money.of(amount, currency))
                    .method(toPaymentMethod(method))
                    .status(PaymentStatus.PROCESSING)
                    .build();

            String cacheKey = "payment:" + paymentId;
            cacheService.cacheData(cacheKey, payment, PAYMENT_CACHE_TTL_SECONDS);
            paymentRecordRepository.save(PaymentRecord.builder()
                    .paymentId(paymentId)
                    .orderId(orderId)
                    .reservationId(reservationId)
                    .customerId(customerId)
                    .amount(amount)
                    .currency(currency)
                    .method(method)
                    .status(PaymentStatus.PROCESSING.name())
                    .createdAt(payment.getCreatedAt())
                    .build());

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
            } else {
                payment.markAsFailed(pgResult.getErrorMessage());
                log.warn("Payment failed: paymentId={}, reason={}", paymentId, pgResult.getErrorMessage());
            }

            cacheService.cacheData(cacheKey, payment, PAYMENT_CACHE_TTL_SECONDS);
            paymentRecordRepository.save(toRecord(payment, method));
            return payment;

        } catch (Exception e) {
            log.error("Error processing payment: paymentId={}", paymentId, e);

            Payment failedPayment = Payment.builder()
                    .paymentId(paymentId)
                    .orderId(orderId)
                    .reservationId(reservationId)
                    .customerId(customerId)
                    .amount(Money.of(amount, currency))
                    .method(toPaymentMethod(method))
                    .status(PaymentStatus.FAILED)
                    .failureReason("시스템 오류: " + e.getMessage())
                    .processedAt(LocalDateTime.now())
                    .build();

            String cacheKey = "payment:" + paymentId;
            cacheService.cacheData(cacheKey, failedPayment, PAYMENT_CACHE_TTL_SECONDS);
            paymentRecordRepository.save(toRecord(failedPayment, method));

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

            PaymentGatewayService gateway = gatewayFactory.getGateway(payment.getMethod().name());
            boolean refunded = gateway.refundPayment(payment.getTransactionId());

            if (refunded) {
                payment.markAsRefunded();

                String cacheKey = "payment:" + paymentId;
                cacheService.cacheData(cacheKey, payment, PAYMENT_CACHE_TTL_SECONDS);
                paymentRecordRepository.save(toRecord(payment, payment.getMethod().name()));

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
     */
    public Payment getPayment(String paymentId) {
        try {
            String cacheKey = "payment:" + paymentId;
            Payment cachedData = cacheService.getCachedObject(cacheKey, Payment.class);

            if (cachedData != null) {
                log.debug("Payment found in cache: paymentId={}", paymentId);
                return cachedData;
            }

            Payment payment = paymentRecordRepository.findById(paymentId)
                    .map(this::toDomainPayment)
                    .orElse(null);
            if (payment != null) {
                cacheService.cacheData(cacheKey, payment, PAYMENT_CACHE_TTL_SECONDS);
                log.debug("Payment found in Postgres: paymentId={}", paymentId);
                return payment;
            }

            log.debug("Payment not found: paymentId={}", paymentId);
            return null;

        } catch (Exception e) {
            log.error("Error getting payment: paymentId={}", paymentId, e);
            return null;
        }
    }

    public Payment getPaymentByReservationId(String reservationId) {
        try {
            return paymentRecordRepository.findByReservationId(reservationId)
                    .map(this::toDomainPayment)
                    .orElse(null);
        } catch (Exception e) {
            log.error("Error getting payment by reservation: reservationId={}", reservationId, e);
            return null;
        }
    }

    public PaymentResponse getPaymentStatusByReservationId(String reservationId) {
        try {
            Payment payment = getPaymentByReservationId(reservationId);
            return payment != null ? convertToResponse(payment) : null;
        } catch (Exception e) {
            log.error("Error getting payment status by reservation: reservationId={}", reservationId, e);
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
                .message(payment.getFailureReason())
                .processedAt(payment.getProcessedAt())
                .build();
    }

    private Payment toDomainPayment(PaymentRecord record) {
        Payment payment = Payment.builder()
                .paymentId(record.getPaymentId())
                .orderId(record.getOrderId())
                .reservationId(record.getReservationId())
                .customerId(record.getCustomerId())
                .amount(Money.of(record.getAmount(), record.getCurrency()))
                .method(toPaymentMethod(record.getMethod()))
                .status(PaymentStatus.valueOf(record.getStatus()))
                .transactionId(record.getTransactionId())
                .failureReason(record.getFailureReason())
                .processedAt(record.getProcessedAt())
                .approvalNumber(record.getApprovalNumber())
                .gatewayName(record.getGatewayName())
                .build();
        payment.setCreatedAt(record.getCreatedAt());
        payment.setUpdatedAt(record.getUpdatedAt());
        return payment;
    }

    private PaymentRecord toRecord(Payment payment, String method) {
        return PaymentRecord.builder()
                .paymentId(payment.getPaymentId())
                .orderId(payment.getOrderId())
                .reservationId(payment.getReservationId())
                .customerId(payment.getCustomerId())
                .amount(payment.getAmount().getAmount())
                .currency(payment.getAmount().getCurrency())
                .method(method)
                .status(payment.getStatus().name())
                .transactionId(payment.getTransactionId())
                .approvalNumber(payment.getApprovalNumber())
                .gatewayName(payment.getGatewayName())
                .failureReason(payment.getFailureReason())
                .processedAt(payment.getProcessedAt())
                .createdAt(payment.getCreatedAt())
                .updatedAt(payment.getUpdatedAt())
                .build();
    }

    private PaymentMethod toPaymentMethod(String method) {
        try {
            return PaymentMethod.valueOf(method);
        } catch (Exception e) {
            return PaymentMethod.CREDIT_CARD;
        }
    }
}

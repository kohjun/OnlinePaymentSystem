package com.example.payment.application.service;

import com.example.payment.application.dto.PaymentGatewayRequest;
import com.example.payment.application.dto.PaymentGatewayResult;
import com.example.payment.domain.entity.PaymentRecord;
import com.example.payment.domain.model.common.Money;
import com.example.payment.domain.model.payment.Payment;
import com.example.payment.domain.model.payment.PaymentMethod;
import com.example.payment.domain.model.payment.PaymentStatus;
import com.example.payment.domain.repository.PaymentRecordRepository;
import com.example.payment.domain.repository.RefundRecordRepository;
import com.example.payment.domain.service.PaymentGatewayService;
import com.example.payment.infrastructure.gateway.PaymentGatewayFactory;
import com.example.payment.infrastructure.messaging.outbox.OutboxEventService;
import com.example.payment.infrastructure.persistence.redis.repository.CacheService;
import com.example.payment.infrastructure.util.IdGenerator;
import com.example.payment.presentation.dto.request.PaymentProcessRequest;
import com.example.payment.presentation.dto.response.PaymentResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 결제 처리 서비스 (WAL 의존성 제거)
 */
@Service
@Slf4j
public class PaymentProcessingService {

    private final PaymentGatewayFactory gatewayFactory;
    private final CacheService cacheService;
    private final PaymentRecordRepository paymentRecordRepository;
    private final RefundCoordinator refundCoordinator;

    @Autowired
    public PaymentProcessingService(PaymentGatewayFactory gatewayFactory,
                                    CacheService cacheService,
                                    PaymentRecordRepository paymentRecordRepository,
                                    RefundRecordRepository refundRecordRepository,
                                    OutboxEventService outboxEventService,
                                    RefundCoordinator refundCoordinator) {
        this.gatewayFactory = gatewayFactory;
        this.cacheService = cacheService;
        this.paymentRecordRepository = paymentRecordRepository;
        this.refundCoordinator = refundCoordinator;
    }

    public PaymentProcessingService(PaymentGatewayFactory gatewayFactory,
                                    CacheService cacheService,
                                    PaymentRecordRepository paymentRecordRepository,
                                    RefundRecordRepository refundRecordRepository,
                                    OutboxEventService outboxEventService) {
        this(gatewayFactory, cacheService, paymentRecordRepository, refundRecordRepository, outboxEventService,
                new RefundCoordinator(gatewayFactory,
                        new RefundLedgerWriter(paymentRecordRepository, refundRecordRepository, outboxEventService)));
    }

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
        return refundPayment(paymentId, "manual-" + paymentId, "Manual refund request");
    }

    public boolean refundPayment(String paymentId, String idempotencyKey, String reason) {
        return refundPaymentWithResult(paymentId, idempotencyKey, reason).success();
    }

    public RefundResult refundPaymentWithResult(String paymentId, String idempotencyKey, String reason) {
        RefundCoordinator.RefundOutcome outcome = refundCoordinator.refund(paymentId, idempotencyKey, reason);
        PaymentResponse payment = null;
        if (outcome.payment() != null) {
            cachePayment(outcome.payment());
            payment = convertToResponse(toDomainPayment(outcome.payment()));
        }
        return new RefundResult(outcome.success(), outcome.code(), outcome.message(), payment);
    }
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

    private void cachePayment(PaymentRecord payment) {
        cacheService.cacheData("payment:" + payment.getPaymentId(), toDomainPayment(payment), PAYMENT_CACHE_TTL_SECONDS);
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
    private PaymentMethod toPaymentMethod(String method) {
        try {
            return PaymentMethod.valueOf(method);
        } catch (Exception e) {
            return PaymentMethod.CREDIT_CARD;
        }
    }
    public record RefundResult(
            boolean success,
            String code,
            String message,
            PaymentResponse payment
    ) {
    }
}

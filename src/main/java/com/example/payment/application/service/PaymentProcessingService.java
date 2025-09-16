/**
 * ========================================
 * 2. PaymentProcessingService (결제 처리 전담)
 * ========================================
 */
package com.example.payment.application.service;

import com.example.payment.domain.exception.PaymentException;
import com.example.payment.domain.model.payment.Payment;
import com.example.payment.infrastructure.persistance.redis.repository.CacheService;
import com.example.payment.application.dto.PaymentGatewayResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentProcessingService {

    private final ExternalPaymentGateway paymentGateway;
    private final CacheService cacheService;

    /**
     * 결제 처리 - 도메인 객체만 반환
     */
    public Payment processPayment(String paymentId, String orderId, String reservationId,
                                  String customerId, BigDecimal amount, String currency, String method) {

        log.info("Processing payment: paymentId={}, orderId={}, amount={}", paymentId, orderId, amount);

        try {
            // 외부 PG 결제 요청
            PaymentGatewayRequest pgRequest = PaymentGatewayRequest.builder()
                    .paymentId(paymentId)
                    .customerId(customerId)
                    .amount(amount)
                    .currency(currency)
                    .method(method)
                    .build();

            PaymentGatewayResult pgResult = paymentGateway.processPayment(pgRequest);

            Payment payment = Payment.builder()
                    .paymentId(paymentId)
                    .orderId(orderId)
                    .reservationId(reservationId)
                    .customerId(customerId)
                    .amount(amount)
                    .currency(currency)
                    .method(method)
                    .status(pgResult.isSuccess() ? "COMPLETED" : "FAILED")
                    .transactionId(pgResult.getTransactionId())
                    .processedAt(LocalDateTime.now())
                    .build();

            // 결과 캐싱
            String cacheKey = "payment:" + paymentId;
            cacheService.cacheData(cacheKey, payment, 86400);

            log.info("Payment processed: paymentId={}, status={}", paymentId, payment.getStatus());
            return payment;

        } catch (Exception e) {
            log.error("Error processing payment: paymentId={}", paymentId, e);
            throw new PaymentException("결제 처리 중 오류 발생", e);
        }
    }

    /**
     * 결제 환불
     */
    public boolean refundPayment(String paymentId) {
        try {
            log.info("Refunding payment: paymentId={}", paymentId);

            // 결제 정보 조회
            String cacheKey = "payment:" + paymentId;
            Object cachedData = cacheService.getCachedData(cacheKey);

            if (cachedData != null) {
                Payment payment = (Payment) cachedData;

                // 외부 PG 환불 요청
                boolean refunded = paymentGateway.refundPayment(payment.getTransactionId());

                if (refunded) {
                    payment.setStatus("REFUNDED");
                    cacheService.cacheData(cacheKey, payment, 86400);

                    log.info("Payment refunded: paymentId={}", paymentId);
                    return true;
                }
            }

            return false;

        } catch (Exception e) {
            log.error("Error refunding payment: paymentId={}", paymentId, e);
            return false;
        }
    }
}
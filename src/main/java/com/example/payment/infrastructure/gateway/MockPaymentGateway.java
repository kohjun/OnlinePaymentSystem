package com.example.payment.infrastructure.gateway;

import com.example.payment.application.dto.PaymentGatewayRequest;
import com.example.payment.application.dto.PaymentGatewayResult;
import com.example.payment.domain.service.PaymentGatewayService;
import com.example.payment.infrastructure.util.IdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 모의 결제 게이트웨이 (개발/테스트용)
 * - 개발환경에서 기본 구현체로 사용
 * - 실제 PG 연동 전까지 테스트용으로 활용
 * * ✅ TPS 측정 모드: 지연 시간 0ms, 성공률 100%로 설정
 */
@Component
@Primary // 개발환경에서 기본 구현체
@Slf4j
public class MockPaymentGateway implements PaymentGatewayService {

    // [수정 1] 성공률을 100%로 고정
    private final double successRate = 1.0;

    // [수정 2] 지연 시간을 0ms로 고정
    private final int minDelayMs = 100;
    private final int maxDelayMs = 500;

    public MockPaymentGateway() {
        log.info("MockPaymentGateway initialized with success rate: {}%, delay: {}-{}ms (TPS Optimized)",
                (successRate * 100), minDelayMs, maxDelayMs);
    }

    @Override
    public PaymentGatewayResult processPayment(PaymentGatewayRequest request) {
        log.debug("Processing mock payment: paymentId={}, amount={}, method={}",
                request.getPaymentId(), request.getAmount(), request.getMethod());

        try {
            // [수정 3] 의도적 지연 제거
            // int delay = ThreadLocalRandom.current().nextInt(minDelayMs, maxDelayMs);
            // Thread.sleep(delay);

            // [수정 4] 100% 성공 보장
            boolean success = true;

            if (success) {
                String transactionId = IdGenerator.generateTransactionId();
                String approvalNumber = "MOCK-" + System.currentTimeMillis();

                log.debug("Mock payment succeeded: paymentId={}, transactionId={}",
                        request.getPaymentId(), transactionId);

                return PaymentGatewayResult.builder()
                        .success(true)
                        .transactionId(transactionId)
                        .approvalNumber(approvalNumber)
                        .processedAmount(request.getAmount())
                        .currency(request.getCurrency())
                        .processedAt(LocalDateTime.now())
                        .gatewayName(getGatewayName())
                        .build();

            } else {
                // 이 블록은 실행되지 않음 (success=true)
                String errorCode = "MOCK_FAILURE_" + ThreadLocalRandom.current().nextInt(1000, 9999);
                String errorMessage = "모의 결제 실패 (테스트용)";

                log.warn("Mock payment failed: paymentId={}, errorCode={}",
                        request.getPaymentId(), errorCode);

                return PaymentGatewayResult.failure(errorCode, errorMessage);
            }

        } catch (Exception e) {
            log.error("Mock payment error: paymentId={}", request.getPaymentId(), e);

            return PaymentGatewayResult.failure("MOCK_ERROR", "시스템 오류: " + e.getMessage());
        }
    }

    @Override
    public boolean refundPayment(String transactionId) {
        log.debug("Processing mock refund: transactionId={}", transactionId);

        try {
            // [수정 5] 의도적 지연 제거
            // Thread.sleep(ThreadLocalRandom.current().nextInt(300, 1000));

            // 100% 환불 성공률
            return true;

        } catch (Exception e) {
            log.error("Mock refund error: transactionId={}", transactionId, e);
            return false;
        }
    }

    @Override
    public PaymentGatewayResult getPaymentStatus(String transactionId) {
        log.debug("Getting mock payment status: transactionId={}", transactionId);

        try {
            return PaymentGatewayResult.builder()
                    .success(true)
                    .transactionId(transactionId)
                    .processedAmount(java.math.BigDecimal.valueOf(100.00))
                    .currency("KRW")
                    .processedAt(LocalDateTime.now().minusMinutes(5))
                    .gatewayName(getGatewayName())
                    .build();

        } catch (Exception e) {
            log.error("Mock payment status check error: transactionId={}", transactionId, e);
            return PaymentGatewayResult.failure("MOCK_STATUS_ERROR", "상태 조회 실패");
        }
    }

    @Override
    public String getGatewayName() {
        return "MOCK_PAYMENT_GATEWAY";
    }

    @Override
    public boolean isHealthy() {
        return true;
    }
}
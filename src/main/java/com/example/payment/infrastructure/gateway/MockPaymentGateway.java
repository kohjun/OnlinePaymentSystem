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
 */
@Component
@Primary // 개발환경에서 기본 구현체
@Slf4j
public class MockPaymentGateway implements PaymentGatewayService {

    private final double successRate;
    private final int minDelayMs;
    private final int maxDelayMs;

    public MockPaymentGateway() {
        // 기본값으로 초기화
        this.successRate = 0.9; // 90% 성공률
        this.minDelayMs = 500;
        this.maxDelayMs = 2000;

        log.info("MockPaymentGateway initialized with success rate: {}%, delay: {}-{}ms",
                (successRate * 100), minDelayMs, maxDelayMs);
    }

    @Override
    public PaymentGatewayResult processPayment(PaymentGatewayRequest request) {
        log.info("Processing mock payment: paymentId={}, amount={}, method={}",
                request.getPaymentId(), request.getAmount(), request.getMethod());

        try {
            // 의도적 지연 (실제 PG 통신 시뮬레이션)
            int delay = ThreadLocalRandom.current().nextInt(minDelayMs, maxDelayMs);
            Thread.sleep(delay);

            // 설정된 성공률로 시뮬레이션
            boolean success = ThreadLocalRandom.current().nextDouble() < successRate;

            if (success) {
                String transactionId = IdGenerator.generateTransactionId();
                String approvalNumber = "MOCK-" + System.currentTimeMillis();

                log.info("Mock payment succeeded: paymentId={}, transactionId={}, delay={}ms",
                        request.getPaymentId(), transactionId, delay);

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
                String errorCode = "MOCK_FAILURE_" + ThreadLocalRandom.current().nextInt(1000, 9999);
                String errorMessage = "모의 결제 실패 (테스트용)";

                log.warn("Mock payment failed: paymentId={}, errorCode={}, delay={}ms",
                        request.getPaymentId(), errorCode, delay);

                return PaymentGatewayResult.builder()
                        .success(false)
                        .errorCode(errorCode)
                        .errorMessage(errorMessage)
                        .processedAt(LocalDateTime.now())
                        .gatewayName(getGatewayName())
                        .build();
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Mock payment interrupted: paymentId={}", request.getPaymentId(), e);

            return PaymentGatewayResult.failure("MOCK_INTERRUPTED", "결제 처리 중단됨");
        } catch (Exception e) {
            log.error("Mock payment error: paymentId={}", request.getPaymentId(), e);

            return PaymentGatewayResult.failure("MOCK_ERROR", "시스템 오류: " + e.getMessage());
        }
    }

    @Override
    public boolean refundPayment(String transactionId) {
        log.info("Processing mock refund: transactionId={}", transactionId);

        try {
            // 의도적 지연
            Thread.sleep(ThreadLocalRandom.current().nextInt(300, 1000));

            // 95% 환불 성공률
            boolean success = ThreadLocalRandom.current().nextDouble() < 0.95;

            if (success) {
                log.info("Mock refund succeeded: transactionId={}", transactionId);
                return true;
            } else {
                log.warn("Mock refund failed: transactionId={}", transactionId);
                return false;
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Mock refund interrupted: transactionId={}", transactionId, e);
            return false;
        } catch (Exception e) {
            log.error("Mock refund error: transactionId={}", transactionId, e);
            return false;
        }
    }

    @Override
    public PaymentGatewayResult getPaymentStatus(String transactionId) {
        log.debug("Getting mock payment status: transactionId={}", transactionId);

        try {
            // 간단한 상태 조회 시뮬레이션
            return PaymentGatewayResult.builder()
                    .success(true)
                    .transactionId(transactionId)
                    .processedAmount(java.math.BigDecimal.valueOf(100.00)) // 임시값
                    .currency("KRW")
                    .processedAt(LocalDateTime.now().minusMinutes(5)) // 5분 전에 처리된 것으로 가정
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
        // 모의 게이트웨이는 항상 건강함
        return true;
    }
}
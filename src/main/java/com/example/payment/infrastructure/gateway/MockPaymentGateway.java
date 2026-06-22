package com.example.payment.infrastructure.gateway;

import com.example.payment.application.dto.PaymentGatewayRequest;
import com.example.payment.application.dto.PaymentGatewayResult;
import com.example.payment.domain.service.PaymentGatewayService;
import com.example.payment.infrastructure.util.IdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name = "payment.mock.enabled", havingValue = "true")
@Slf4j
public class MockPaymentGateway implements PaymentGatewayService {

    private final Map<String, PaymentGatewayResult> processedPayments = new ConcurrentHashMap<>();
    private final Map<String, PaymentGatewayResult> paymentsByTransactionId = new ConcurrentHashMap<>();

    @Override
    public PaymentGatewayResult processPayment(PaymentGatewayRequest request) {
        PaymentGatewayResult existing = processedPayments.get(request.getPaymentId());
        if (existing != null) {
            return existing;
        }

        String transactionId = IdGenerator.generateTransactionId();
        PaymentGatewayResult result = PaymentGatewayResult.builder()
                .success(true)
                .gatewayStatus("APPROVED")
                .transactionId(transactionId)
                .approvalNumber("MOCK-" + System.currentTimeMillis())
                .processedAmount(request.getAmount())
                .currency(request.getCurrency())
                .processedAt(LocalDateTime.now())
                .gatewayName(getGatewayName())
                .build();
        processedPayments.put(request.getPaymentId(), result);
        paymentsByTransactionId.put(transactionId, result);
        return result;
    }

    @Override
    public boolean refundPayment(String transactionId) {
        return paymentsByTransactionId.containsKey(transactionId);
    }

    @Override
    public PaymentGatewayResult getPaymentStatus(String transactionId) {
        PaymentGatewayResult result = paymentsByTransactionId.get(transactionId);
        return result != null
                ? result
                : PaymentGatewayResult.failure("MOCK_STATUS_NOT_FOUND", "payment transaction not found");
    }

    @Override
    public PaymentGatewayResult getPaymentStatusByPaymentId(String paymentId) {
        PaymentGatewayResult result = processedPayments.get(paymentId);
        return result != null
                ? result
                : PaymentGatewayResult.failure("MOCK_STATUS_NOT_FOUND", "payment not found");
    }

    @Override
    public String getGatewayName() {
        return "MOCK_PAYMENT_GATEWAY";
    }

    @Override
    public boolean supports(String paymentMethod) {
        if (paymentMethod == null) {
            return false;
        }
        return switch (paymentMethod.toUpperCase().trim()) {
            case "MOCK", "TEST", "CREDIT_CARD", "DEBIT_CARD", "TOSS_PAY", "BANK_TRANSFER", "MOBILE_PAY" -> true;
            default -> false;
        };
    }

    @Override
    public boolean isHealthy() {
        return true;
    }
}

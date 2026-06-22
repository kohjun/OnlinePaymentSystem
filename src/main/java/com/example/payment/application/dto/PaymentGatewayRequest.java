package com.example.payment.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PaymentGatewayRequest {

    @NotBlank(message = "paymentId is required")
    private String paymentId;

    private String idempotencyKey;

    @NotBlank(message = "customerId is required")
    private String customerId;

    @NotNull(message = "amount is required")
    @Positive(message = "amount must be greater than zero")
    private BigDecimal amount;

    @NotBlank(message = "currency is required")
    private String currency;

    @NotBlank(message = "payment method is required")
    private String method;

    private String cardNumber;
    private String cardHolderName;
    private String merchantId;
    private String orderName;

    private String tossPaymentKey;
    private String tossOrderId;
    private String tossIntentId;

    private String successUrl;
    private String failUrl;
    private String cancelUrl;

    private String userAgent;
    private String clientIp;
}

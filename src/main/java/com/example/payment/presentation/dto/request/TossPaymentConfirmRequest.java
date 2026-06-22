package com.example.payment.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TossPaymentConfirmRequest {

    @NotBlank(message = "intentId is required")
    private String intentId;

    @NotBlank(message = "paymentKey is required")
    private String paymentKey;

    @NotBlank(message = "orderId is required")
    private String orderId;

    @NotNull(message = "amount is required")
    private BigDecimal amount;
}

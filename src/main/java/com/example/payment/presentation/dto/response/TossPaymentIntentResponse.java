package com.example.payment.presentation.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TossPaymentIntentResponse {
    private String intentId;
    private String orderId;
    private String orderName;
    private BigDecimal amount;
    private String currency;
    private String customerKey;
    private String clientKey;
    private String successUrl;
    private String failUrl;
    private String status;
    private LocalDateTime expiresAt;
}

package com.example.payment.presentation.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentResponse {
    private String paymentId;
    private String orderId;
    private BigDecimal amount;
    private String currency;
    private String status; // CREATED, PROCESSING, COMPLETED, FAILED
    private String message;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
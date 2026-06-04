package com.example.payment.application.temporal;

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
public class ReservationWorkflowStepResult {
    private boolean success;
    private String message;
    private String reservationId;
    private String orderId;
    private String paymentId;
    private String transactionId;
    private String approvalNumber;
    private String gatewayName;
    private BigDecimal amount;
    private String currency;
    private String status;
    private LocalDateTime processedAt;
    private LocalDateTime expiresAt;

    public static ReservationWorkflowStepResult success(String message) {
        return ReservationWorkflowStepResult.builder()
                .success(true)
                .message(message)
                .build();
    }

    public static ReservationWorkflowStepResult failure(String message) {
        return ReservationWorkflowStepResult.builder()
                .success(false)
                .message(message)
                .build();
    }
}

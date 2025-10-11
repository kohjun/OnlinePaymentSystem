package com.example.payment.presentation.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentProcessRequest {

    @NotBlank(message = "결제 ID는 필수입니다")
    private String paymentId;

    @NotBlank(message = "예약 ID는 필수입니다")
    private String reservationId;

    @NotBlank(message = "고객 ID는 필수입니다")
    private String customerId;

    @NotNull(message = "금액은 필수입니다")
    @Positive(message = "금액은 0보다 커야 합니다")
    private BigDecimal amount;

    @NotBlank(message = "통화는 필수입니다")
    private String currency;

    @NotBlank(message = "결제 수단은 필수입니다")
    private String paymentMethod;

    private String orderId;
    private String clientId;
    private String idempotencyKey;
}
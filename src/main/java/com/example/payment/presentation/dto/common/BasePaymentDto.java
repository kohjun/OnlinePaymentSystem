package com.example.payment.presentation.dto.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public abstract class BasePaymentDto {

    @NotNull(message = "금액은 필수입니다")
    @DecimalMin(value = "0.01", message = "금액은 0보다 커야 합니다")
    protected BigDecimal amount;

    @NotBlank(message = "통화는 필수입니다")
    protected String currency;

    @NotBlank(message = "결제 수단은 필수입니다")
    protected String paymentMethod;

    // 결제 추가 정보
    protected String cardNumber; // 마스킹된 카드번호 (끝 4자리)
    protected String cardHolderName;
}
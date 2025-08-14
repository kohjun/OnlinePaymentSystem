package com.example.payment.presentation.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OrderRequest {

    @NotNull(message = "고객 ID는 필수입니다")
    private String customerId;

    @NotNull(message = "상품 ID는 필수입니다")
    private String productId;

    @NotNull(message = "수량은 필수입니다")
    @Positive(message = "수량은 양수여야 합니다")
    private Integer quantity;

    @NotNull(message = "금액은 필수입니다")
    @Positive(message = "금액은 양수여야 합니다")
    private BigDecimal amount;

    @NotNull(message = "통화는 필수입니다")
    private String currency;

    private String clientId;
    private String idempotencyKey;
}
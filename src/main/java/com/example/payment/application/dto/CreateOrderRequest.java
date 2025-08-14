/**
 * 주문 생성 요청 DTO
 */
package com.example.payment.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CreateOrderRequest {

    @NotBlank(message = "고객 ID는 필수입니다")
    private String customerId;

    @NotBlank(message = "상품 ID는 필수입니다")
    private String productId;

    @NotNull(message = "수량은 필수입니다")
    @Positive(message = "수량은 1 이상이어야 합니다")
    private Integer quantity;

    @NotNull(message = "금액은 필수입니다")
    @Positive(message = "금액은 0보다 커야 합니다")
    private BigDecimal amount;

    @NotBlank(message = "통화는 필수입니다")
    private String currency;

    @NotBlank(message = "결제 ID는 필수입니다")
    private String paymentId;

    @NotBlank(message = "예약 ID는 필수입니다")
    private String reservationId;

    // 추가 정보들
    private String shippingAddress;
    private String specialInstructions;
}
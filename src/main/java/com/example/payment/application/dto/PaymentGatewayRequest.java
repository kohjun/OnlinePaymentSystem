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
public class PaymentGatewayRequest {

    @NotBlank(message = "결제 ID는 필수입니다")
    private String paymentId;

    @NotBlank(message = "고객 ID는 필수입니다")
    private String customerId;

    @NotNull(message = "금액은 필수입니다")
    @Positive(message = "금액은 0보다 커야 합니다")
    private BigDecimal amount;

    @NotBlank(message = "통화는 필수입니다")
    private String currency;

    @NotBlank(message = "결제 수단은 필수입니다")
    private String method;

    // 추가 결제 정보
    private String cardNumber; // 마스킹된 카드번호
    private String cardHolderName;
    private String merchantId;
    private String orderName; // PG사에 전달할 주문명

    // 콜백 URL들
    private String successUrl;
    private String failUrl;
    private String cancelUrl;

    // 추가 메타데이터
    private String userAgent;
    private String clientIp;
}
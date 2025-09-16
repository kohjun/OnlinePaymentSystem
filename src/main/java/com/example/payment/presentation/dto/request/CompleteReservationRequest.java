package com.example.payment.presentation.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;

/**
 * 통합 예약 요청 DTO
 * - 재고 선점부터 결제 확정까지 모든 정보 포함
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompleteReservationRequest {

    @NotBlank(message = "고객 ID는 필수입니다")
    private String customerId;

    @NotBlank(message = "상품 ID는 필수입니다")
    private String productId;

    @NotNull(message = "수량은 필수입니다")
    @Positive(message = "수량은 1 이상이어야 합니다")
    private Integer quantity;

    @NotNull(message = "금액은 필수입니다")
    @DecimalMin(value = "0.01", message = "금액은 0보다 커야 합니다")
    private BigDecimal amount;

    @NotBlank(message = "통화는 필수입니다")
    private String currency;

    @NotBlank(message = "결제 수단은 필수입니다")
    private String paymentMethod;

    private String clientId; // 클라이언트 구분용 (web, mobile-app 등)
    private String idempotencyKey; // 멱등성 키
    private String userAgent; // 사용자 환경 정보
    private String ipAddress; // 보안용

    // 추가 결제 정보 (선택적)
    private String cardNumber; // 마스킹된 카드번호 (끝 4자리만)
    private String cardHolderName;

    // 배송 정보 (선택적)
    private String shippingAddress;
    private String shippingMethod;
    private String specialInstructions;
}
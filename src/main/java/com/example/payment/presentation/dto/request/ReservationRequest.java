package com.example.payment.presentation.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotBlank;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReservationRequest {

    @NotBlank(message = "상품 ID는 필수입니다")
    private String productId;

    @NotBlank(message = "고객 ID는 필수입니다")
    private String customerId;

    @NotNull(message = "수량은 필수입니다")
    @Positive(message = "수량은 1 이상이어야 합니다")
    private Integer quantity;

    private String clientId; // 클라이언트 구분용 (mobile-app, web 등)
    private String userAgent; // 사용자 환경 정보
    private String ipAddress; // 보안용
}
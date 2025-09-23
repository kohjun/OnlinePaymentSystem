package com.example.payment.presentation.dto.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public abstract class BaseReservationDto {

    @NotBlank(message = "상품 ID는 필수입니다")
    protected String productId;

    @NotBlank(message = "고객 ID는 필수입니다")
    protected String customerId;

    @NotNull(message = "수량은 필수입니다")
    @Positive(message = "수량은 1 이상이어야 합니다")
    protected Integer quantity;

    // 메타 정보
    protected String clientId; // web, mobile-app 등
    protected String userAgent; // 사용자 환경
    protected String ipAddress; // 보안용
}
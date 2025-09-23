/**
 * 통합 예약+결제 요청 (Phase 1+2)
 */
package com.example.payment.presentation.dto.request;

import com.example.payment.presentation.dto.common.BaseReservationDto;
import com.example.payment.presentation.dto.common.BasePaymentDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class CompleteReservationRequest extends BaseReservationDto {

    // 결제 정보 포함 (조합 패턴)
    private PaymentInfo paymentInfo;

    // 배송 정보 (선택적)
    private ShippingInfo shippingInfo;

    // 멱등성 및 추적
    private String idempotencyKey; // 멱등성 키
    private String correlationId;  // 추적용

    @Data
    @SuperBuilder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentInfo extends BasePaymentDto {
        // 추가 결제 전용 필드들
        private String merchantId;
        private String orderName; // PG사 전달용 주문명

        // 콜백 URL들
        private String successUrl;
        private String failUrl;
        private String cancelUrl;
    }

    @Data
    @SuperBuilder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ShippingInfo {
        private String address;
        private String method; // STANDARD, EXPRESS 등
        private String specialInstructions;
        private String contactPhone;
    }
}
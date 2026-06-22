package com.example.payment.presentation.dto.request;

import com.example.payment.presentation.dto.common.BasePaymentDto;
import com.example.payment.presentation.dto.common.BaseReservationDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

    @Valid
    @NotNull(message = "paymentInfo is required")
    private PaymentInfo paymentInfo;

    @Valid
    private ShippingInfo shippingInfo;

    @NotBlank(message = "idempotencyKey is required")
    private String idempotencyKey;

    private String correlationId;

    private String seatId;

    @Data
    @SuperBuilder
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode(callSuper = true)
    public static class PaymentInfo extends BasePaymentDto {
        private String merchantId;
        private String orderName;
        private String successUrl;
        private String failUrl;
        private String cancelUrl;
        private String tossPaymentKey;
        private String tossOrderId;
        private String tossIntentId;
    }

    @Data
    @SuperBuilder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ShippingInfo {
        private String address;
        private String method;
        private String specialInstructions;
        private String contactPhone;
    }
}

package com.example.payment.presentation.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class MarketplaceCheckoutRequest {

    @NotBlank(message = "customerId is required")
    private String customerId;

    @NotNull(message = "quantity is required")
    @Positive(message = "quantity must be greater than 0")
    private Integer quantity;

    @NotBlank(message = "idempotencyKey is required")
    private String idempotencyKey;

    private String correlationId;
    private String clientId;
    private String seatId;

    @Valid
    @NotNull(message = "paymentInfo is required")
    private CompleteReservationRequest.PaymentInfo paymentInfo;

    @Valid
    private CompleteReservationRequest.ShippingInfo shippingInfo;
}

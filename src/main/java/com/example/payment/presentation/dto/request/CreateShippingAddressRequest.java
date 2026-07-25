package com.example.payment.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateShippingAddressRequest {
    @Size(max = 100)
    private String label;

    @NotBlank(message = "recipientName is required")
    @Size(max = 100)
    private String recipientName;

    @NotBlank(message = "contactPhone is required")
    @Size(max = 50)
    private String contactPhone;

    @Size(max = 30)
    private String postalCode;

    @NotBlank(message = "addressLine1 is required")
    @Size(max = 500)
    private String addressLine1;

    @Size(max = 500)
    private String addressLine2;

    @Size(max = 500)
    private String deliveryMemo;

    private Boolean defaultAddress;
}

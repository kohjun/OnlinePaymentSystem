package com.example.payment.presentation.dto.response;

import com.example.payment.domain.model.account.ShippingAddressStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ShippingAddressResponse {
    private String addressId;
    private String userId;
    private String customerId;
    private String label;
    private String recipientName;
    private String contactPhone;
    private String postalCode;
    private String addressLine1;
    private String addressLine2;
    private String deliveryMemo;
    private Boolean defaultAddress;
    private ShippingAddressStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

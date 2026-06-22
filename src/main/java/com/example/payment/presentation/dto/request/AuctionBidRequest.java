package com.example.payment.presentation.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class AuctionBidRequest {
    @NotBlank(message = "customerId is required")
    private String customerId;

    @NotNull(message = "bidAmount is required")
    @DecimalMin(value = "0.01", message = "bidAmount must be greater than 0")
    private BigDecimal bidAmount;
}

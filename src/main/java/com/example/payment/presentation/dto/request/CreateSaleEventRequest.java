package com.example.payment.presentation.dto.request;

import com.example.payment.domain.model.marketplace.SaleType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class CreateSaleEventRequest {
    @NotNull(message = "saleType is required")
    private SaleType saleType;

    @NotNull(message = "price is required")
    @DecimalMin(value = "0.01", message = "price must be greater than 0")
    private BigDecimal price;

    @NotNull(message = "stockQuantity is required")
    @Min(value = 1, message = "stockQuantity must be greater than 0")
    private Integer stockQuantity;

    private Boolean publishImmediately;
    private LocalDateTime startsAt;
    private LocalDateTime endsAt;
    private BigDecimal minBidIncrement;
    private BigDecimal reservePrice;
}

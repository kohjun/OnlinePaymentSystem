package com.example.payment.presentation.dto.request;

import com.example.payment.domain.model.marketplace.SaleType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class CreateSellerListingRequest {
    @NotBlank(message = "name is required")
    private String name;

    private String description;

    @NotNull(message = "price is required")
    @DecimalMin(value = "0.01", message = "price must be greater than 0")
    private BigDecimal price;

    @NotBlank(message = "category is required")
    private String category;

    @NotNull(message = "quantity is required")
    @Min(value = 1, message = "quantity must be greater than 0")
    private Integer quantity;

    private SaleType saleType;
    private String imageUrl;
    private String itemCondition;
    private Boolean publishImmediately;
    private LocalDateTime startsAt;
    private LocalDateTime endsAt;
    private BigDecimal minBidIncrement;
    private BigDecimal reservePrice;
}

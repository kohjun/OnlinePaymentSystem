package com.example.payment.presentation.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class UpdateC2CListingRequest {
    @Size(max = 255)
    private String name;

    @Size(max = 1000)
    private String description;

    @DecimalMin(value = "0.01", message = "price must be greater than 0")
    private BigDecimal price;

    @Size(max = 100)
    private String category;

    @Min(value = 1, message = "quantity must be greater than 0")
    private Integer quantity;

    @Size(max = 1000)
    private String imageUrl;

    @Size(max = 100)
    private String itemCondition;

    @Size(max = 255)
    private String brand;

    @Size(max = 1000)
    private String tags;

    @Size(max = 1000)
    private String authenticityNote;

    @Size(max = 1000)
    private String defectDescription;
}

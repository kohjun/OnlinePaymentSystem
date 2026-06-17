package com.example.payment.presentation.dto.request;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class ProductDto {
    private String name;
    private String description;
    private BigDecimal price;
    private String category; // "TICKETING", "DRAW", "AUCTION"
    private Integer quantity;
}

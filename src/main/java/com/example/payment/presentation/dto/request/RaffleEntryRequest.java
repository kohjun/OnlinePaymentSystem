package com.example.payment.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RaffleEntryRequest {
    @NotBlank(message = "customerId is required")
    private String customerId;
}

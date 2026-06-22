package com.example.payment.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateSellerRequest {
    @NotBlank(message = "displayName is required")
    private String displayName;
}

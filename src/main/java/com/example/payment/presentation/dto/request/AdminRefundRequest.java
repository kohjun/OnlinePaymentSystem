package com.example.payment.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminRefundRequest {

    @NotBlank(message = "idempotencyKey is required")
    private String idempotencyKey;

    private String reason;
}
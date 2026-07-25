package com.example.payment.presentation.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ReviewSellerPayoutAccountRequest {

    @NotNull(message = "approved is required")
    private Boolean approved;

    @Size(max = 1000)
    private String note;
}

package com.example.payment.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SubmitSellerVerificationRequest {

    @NotBlank(message = "evidenceRef is required")
    @Size(max = 500)
    private String evidenceRef;

    @Size(max = 1000)
    private String note;
}

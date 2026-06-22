package com.example.payment.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ReviewListingRequest {

    @NotBlank
    private String operatorId;

    @Size(max = 1000)
    private String note;
}

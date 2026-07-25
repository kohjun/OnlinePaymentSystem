package com.example.payment.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SubmitSellerPayoutAccountRequest {

    @NotBlank(message = "accountRef is required")
    @Size(max = 500)
    private String accountRef;

    @NotBlank(message = "bankCode is required")
    @Size(max = 50)
    private String bankCode;

    @NotBlank(message = "bankName is required")
    @Size(max = 100)
    private String bankName;

    @NotBlank(message = "accountHolderName is required")
    @Size(max = 100)
    private String accountHolderName;

    @NotBlank(message = "accountLast4 is required")
    @Pattern(regexp = "\\d{4}", message = "accountLast4 must be four digits")
    private String accountLast4;

    @Size(max = 1000)
    private String note;
}

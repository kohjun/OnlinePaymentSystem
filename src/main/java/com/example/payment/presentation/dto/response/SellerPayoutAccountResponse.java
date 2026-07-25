package com.example.payment.presentation.dto.response;

import com.example.payment.domain.model.marketplace.SellerPayoutAccountStatus;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class SellerPayoutAccountResponse {

    private String payoutAccountId;
    private String sellerId;
    private String accountRef;
    private String bankCode;
    private String bankName;
    private String accountHolderName;
    private String accountLast4;
    private SellerPayoutAccountStatus status;
    private String reviewNote;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime submittedAt;

    private String reviewedBy;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime reviewedAt;
}

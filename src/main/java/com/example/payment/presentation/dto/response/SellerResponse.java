package com.example.payment.presentation.dto.response;

import com.example.payment.domain.model.marketplace.SellerStatus;
import com.example.payment.domain.model.marketplace.SellerVerificationStatus;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class SellerResponse {
    private String sellerId;
    private String displayName;
    private SellerStatus status;
    private SellerVerificationStatus verificationStatus;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;
}

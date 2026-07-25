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
    private String ownerUserId;
    private String ownerCustomerId;
    private SellerStatus status;
    private SellerVerificationStatus verificationStatus;
    private String verificationEvidenceRef;
    private String verificationNote;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime verificationSubmittedAt;

    private String verificationReviewedBy;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime verificationReviewedAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;
}

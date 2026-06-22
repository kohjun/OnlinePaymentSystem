package com.example.payment.presentation.dto.response;

import com.example.payment.domain.model.marketplace.SellerPayoutStatus;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class SellerPayoutResponse {

    private String payoutId;
    private String sellerId;
    private String sourceType;
    private String sourceId;
    private BigDecimal grossAmount;
    private BigDecimal platformFee;
    private BigDecimal netAmount;
    private SellerPayoutStatus status;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime releasedAt;
}

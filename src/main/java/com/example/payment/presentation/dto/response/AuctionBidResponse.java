package com.example.payment.presentation.dto.response;

import com.example.payment.domain.model.marketplace.AuctionBidStatus;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class AuctionBidResponse {
    private String bidId;
    private String saleEventId;
    private String customerId;
    private BigDecimal bidAmount;
    private AuctionBidStatus status;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;
}

package com.example.payment.presentation.dto.response;

import com.example.payment.domain.model.marketplace.AuctionSettlementStatus;
import com.example.payment.domain.model.marketplace.AuctionCloseReason;
import com.example.payment.domain.model.marketplace.SaleEventStatus;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class AuctionStatusResponse {
    private String saleEventId;
    private SaleEventStatus eventStatus;
    private BigDecimal highestBid;
    private String highestBidder;
    private BigDecimal minNextBid;
    private AuctionSettlementStatus settlementStatus;
    private Boolean closed;
    private Boolean reserveMet;
    private AuctionCloseReason closeReason;
    private Boolean checkoutAvailable;
    private Integer offerSequence;
    private String allocationStatus;
    private String winnerCustomerId;
    private Boolean currentUserWinning;
    private Long secondsRemaining;
    private Long streamVersion;
    private List<AuctionBidResponse> history;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss['Z']")
    private LocalDateTime endsAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss['Z']")
    private LocalDateTime checkoutExpiresAt;
}

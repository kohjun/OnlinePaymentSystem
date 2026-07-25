package com.example.payment.presentation.dto.response;

import com.example.payment.domain.model.marketplace.RaffleCheckoutStatus;
import com.example.payment.domain.model.marketplace.RaffleEntryStatus;
import com.example.payment.domain.model.marketplace.SaleEventStatus;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class RaffleStatusResponse {
    private String saleEventId;
    private SaleEventStatus eventStatus;
    private long entryCount;
    private long winnerCount;
    private long completedCheckoutCount;
    private Boolean entered;
    private Boolean winner;
    private Boolean drawn;
    private RaffleEntryStatus entryStatus;
    private RaffleCheckoutStatus checkoutStatus;
    private List<String> winnerCustomerIds;
    private List<String> winnerAliases;
    private String drawSeedCommitment;
    private String entrySnapshotHash;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime endsAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime checkoutExpiresAt;
}

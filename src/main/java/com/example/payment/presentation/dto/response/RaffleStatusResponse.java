package com.example.payment.presentation.dto.response;

import com.example.payment.domain.model.marketplace.RaffleCheckoutStatus;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class RaffleStatusResponse {
    private String saleEventId;
    private long entryCount;
    private long winnerCount;
    private long completedCheckoutCount;
    private Boolean entered;
    private Boolean winner;
    private RaffleCheckoutStatus checkoutStatus;
    private List<String> winnerCustomerIds;
}

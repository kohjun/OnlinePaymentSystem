package com.example.payment.presentation.dto.response;

import com.example.payment.domain.model.marketplace.SaleEventStatus;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class TicketSeatMapResponse {
    private String saleEventId;
    private SaleEventStatus eventStatus;
    private int totalCount;
    private int availableCount;
    private int heldCount;
    private int soldCount;
    private long holdSeconds;
    private List<TicketSeatResponse> seats;
}

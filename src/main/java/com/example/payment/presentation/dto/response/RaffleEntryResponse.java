package com.example.payment.presentation.dto.response;

import com.example.payment.domain.model.marketplace.RaffleEntryStatus;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class RaffleEntryResponse {
    private String entryId;
    private String saleEventId;
    private String customerId;
    private RaffleEntryStatus status;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;
}

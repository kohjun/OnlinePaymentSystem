package com.example.payment.presentation.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class TicketSeatHoldResponse {
    private String saleEventId;
    private String seatId;
    private String status;
    private LocalDateTime expiresAt;
}

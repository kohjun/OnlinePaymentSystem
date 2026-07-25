package com.example.payment.presentation.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class TicketSeatResponse {
    private String seatId;
    private String section;
    private String rowLabel;
    private Integer seatNumber;
    private String label;
    private String status;
    private boolean ownedByCurrentUser;
    private LocalDateTime holdExpiresAt;
}

package com.example.payment.presentation.dto.response;

import com.example.payment.domain.model.marketplace.OrderCancellationStatus;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class OrderCancellationResponse {
    private String cancellationId;
    private String marketplaceOrderId;
    private OrderCancellationStatus status;
    private String failureReason;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime completedAt;
}

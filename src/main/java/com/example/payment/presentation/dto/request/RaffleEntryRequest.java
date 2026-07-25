package com.example.payment.presentation.dto.request;

import lombok.Data;

@Data
public class RaffleEntryRequest {
    private String customerId;

    private String idempotencyKey;
}

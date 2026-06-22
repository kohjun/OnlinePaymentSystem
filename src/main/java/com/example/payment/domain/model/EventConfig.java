package com.example.payment.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventConfig {
    private String eventId;
    private String title;
    private String type; // "TICKETING", "DRAW", "AUCTION"
    private int totalInventory;
    private double price;
    private Map<String, Object> extraData;
}

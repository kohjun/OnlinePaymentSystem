package com.example.payment.application.service;

import java.time.Instant;

public record MarketplaceRealtimeEnvelope(
        String originInstanceId,
        String streamType,
        String eventId,
        String eventName,
        Object payload,
        Instant occurredAt
) {
}

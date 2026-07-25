package com.example.payment.application.service;

public interface MarketplaceRealtimePublisher {
    void publish(MarketplaceRealtimeEnvelope envelope);
}

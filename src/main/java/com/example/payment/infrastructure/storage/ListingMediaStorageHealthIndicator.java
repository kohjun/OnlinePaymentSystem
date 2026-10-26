package com.example.payment.infrastructure.storage;

import com.example.payment.application.service.ListingMediaStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("listingMediaStorage")
@RequiredArgsConstructor
public class ListingMediaStorageHealthIndicator implements HealthIndicator {

    private final ListingMediaStorage storage;

    @Override
    public Health health() {
        ListingMediaStorage.StorageReadiness readiness = storage.readiness();
        Health.Builder health = readiness.ready() ? Health.up() : Health.down();
        return health
                .withDetail("provider", storage.providerName())
                .withDetail("message", readiness.message())
                .build();
    }
}

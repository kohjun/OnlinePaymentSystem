package com.example.payment.infrastructure.storage;

import com.example.payment.application.service.ListingMediaScanner;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("listingMediaMalwareScanner")
@RequiredArgsConstructor
public class ListingMediaScannerHealthIndicator implements HealthIndicator {

    private final ListingMediaScanner scanner;

    @Override
    public Health health() {
        ListingMediaScanner.ScannerReadiness readiness = scanner.readiness();
        Health.Builder health = readiness.ready() ? Health.up() : Health.down();
        return health.withDetail("provider", scanner.providerName())
                .withDetail("message", readiness.message())
                .build();
    }
}

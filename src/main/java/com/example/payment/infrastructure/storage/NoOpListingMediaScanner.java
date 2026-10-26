package com.example.payment.infrastructure.storage;

import com.example.payment.application.service.ListingMediaScanner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@ConditionalOnProperty(name = "app.media.malware-scan.provider", havingValue = "NONE", matchIfMissing = true)
public class NoOpListingMediaScanner implements ListingMediaScanner {

    @Override
    public String providerName() {
        return "NONE";
    }

    @Override
    public void assertSafe(Path path, String filename, String contentType) {
        // Local development can opt out. Production readiness rejects this provider.
    }

    @Override
    public ScannerReadiness readiness() {
        return new ScannerReadiness(true, "Malware scanning is disabled for this non-production environment.");
    }
}

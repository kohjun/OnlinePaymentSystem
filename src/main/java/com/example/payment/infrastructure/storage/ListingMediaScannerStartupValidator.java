package com.example.payment.infrastructure.storage;

import com.example.payment.application.service.ListingMediaScanner;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.media.malware-scan.validate-on-startup", havingValue = "true")
public class ListingMediaScannerStartupValidator implements ApplicationRunner {

    private final ListingMediaScanner scanner;

    @Override
    public void run(ApplicationArguments args) {
        ListingMediaScanner.ScannerReadiness readiness = scanner.readiness();
        if (!readiness.ready()) {
            throw new IllegalStateException("Listing media malware scanner is not ready: " + readiness.message());
        }
    }
}

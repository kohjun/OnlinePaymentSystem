package com.example.payment.application.service;

import java.nio.file.Path;

public interface ListingMediaScanner {

    String providerName();

    void assertSafe(Path path, String filename, String contentType);

    ScannerReadiness readiness();

    record ScannerReadiness(boolean ready, String message) {
    }
}

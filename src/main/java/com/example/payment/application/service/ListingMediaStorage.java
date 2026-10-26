package com.example.payment.application.service;

import org.springframework.core.io.Resource;

import java.nio.file.Path;

/**
 * Durable storage boundary for listing images and seller-only evidence files.
 */
public interface ListingMediaStorage {

    String providerName();

    void store(String storageKey, Path source, String contentType);

    Resource load(String storageKey);

    void delete(String storageKey);

    StorageReadiness readiness();

    record StorageReadiness(boolean ready, String message) {
    }
}

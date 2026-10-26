package com.example.payment.infrastructure.storage;

import com.example.payment.application.service.ListingMediaStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.media.validate-on-startup", havingValue = "true")
public class ListingMediaStorageStartupValidator implements ApplicationRunner {

    private final ListingMediaStorage storage;

    @Override
    public void run(ApplicationArguments args) {
        ListingMediaStorage.StorageReadiness readiness = storage.readiness();
        if (!readiness.ready()) {
            throw new IllegalStateException("Listing media storage is not ready: " + readiness.message());
        }
    }
}

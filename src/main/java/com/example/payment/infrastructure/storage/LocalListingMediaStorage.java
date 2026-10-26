package com.example.payment.infrastructure.storage;

import com.example.payment.application.service.ListingMediaStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Component
@ConditionalOnProperty(name = "app.media.storage-provider", havingValue = "LOCAL", matchIfMissing = true)
public class LocalListingMediaStorage implements ListingMediaStorage {

    private final Path root;

    public LocalListingMediaStorage(@Value("${app.media.storage-root:./.data/uploads}") String storageRoot) {
        this.root = Path.of(storageRoot).toAbsolutePath().normalize();
    }

    @Override
    public String providerName() {
        return "LOCAL";
    }

    @Override
    public void store(String storageKey, Path source, String contentType) {
        Path target = resolve(storageKey);
        try {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".upload");
            try {
                Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException atomicMoveFailure) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to store listing media on local disk.", exception);
        }
    }

    @Override
    public Resource load(String storageKey) {
        FileSystemResource resource = new FileSystemResource(resolve(storageKey));
        if (!resource.exists() || !resource.isReadable()) {
            throw new IllegalArgumentException("Stored media file is unavailable.");
        }
        return resource;
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to delete listing media from local disk.", exception);
        }
    }

    @Override
    public StorageReadiness readiness() {
        try {
            Files.createDirectories(root);
            boolean ready = Files.isDirectory(root) && Files.isWritable(root);
            return new StorageReadiness(ready, ready
                    ? "Local media directory is writable."
                    : "Local media directory is not writable: " + root);
        } catch (IOException exception) {
            return new StorageReadiness(false, "Local media directory cannot be prepared: " + root);
        }
    }

    private Path resolve(String storageKey) {
        Path resolved = root.resolve(storageKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Invalid media storage path.");
        }
        return resolved;
    }
}

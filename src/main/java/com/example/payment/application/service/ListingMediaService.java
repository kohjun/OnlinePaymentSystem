package com.example.payment.application.service;

import com.example.payment.domain.model.marketplace.ListingMediaType;
import com.example.payment.domain.model.marketplace.MarketplaceListing;
import com.example.payment.domain.model.marketplace.MarketplaceListingMedia;
import com.example.payment.domain.repository.MarketplaceListingMediaRepository;
import com.example.payment.domain.repository.MarketplaceListingRepository;
import com.example.payment.presentation.dto.response.ListingMediaResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ListingMediaService {

    public static final int MAX_FILES_PER_TYPE = 3;
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
    private static final long MAX_EVIDENCE_BYTES = 15L * 1024 * 1024;

    private final MarketplaceListingRepository listingRepository;
    private final MarketplaceListingMediaRepository mediaRepository;
    private final ListingMediaStorage mediaStorage;
    private final ListingMediaScanner mediaScanner;

    @Transactional(readOnly = true)
    public String sellerIdForListing(String listingId) {
        return requireListing(listingId).getSellerId();
    }

    @Transactional(readOnly = true)
    public List<ListingMediaResponse> listForListing(String listingId) {
        requireListing(listingId);
        return mediaRepository.findByListingIdOrderByMediaTypeAscSortOrderAsc(listingId)
                .stream().map(this::toOwnerResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<ListingMediaResponse> listPublicImages(String listingId) {
        requirePublicListing(listingId);
        return mediaRepository.findByListingIdAndMediaTypeOrderBySortOrderAsc(
                        listingId, ListingMediaType.PRODUCT_IMAGE)
                .stream().map(this::toPublicResponse).toList();
    }

    @Transactional
    public List<ListingMediaResponse> upload(String listingId,
                                             ListingMediaType mediaType,
                                             List<MultipartFile> files) {
        MarketplaceListing listing = listingRepository.findByIdForUpdate(listingId)
                .orElseThrow(() -> new IllegalArgumentException("Listing not found: " + listingId));
        if (files == null || files.isEmpty() || files.stream().allMatch(MultipartFile::isEmpty)) {
            throw new IllegalArgumentException("At least one media file is required.");
        }

        List<MarketplaceListingMedia> existing = mediaRepository
                .findByListingIdAndMediaTypeOrderBySortOrderAsc(listingId, mediaType);
        List<MultipartFile> supplied = files.stream().filter(file -> !file.isEmpty()).toList();
        if (existing.size() + supplied.size() > MAX_FILES_PER_TYPE) {
            throw new IllegalArgumentException("A listing can have at most 3 files for " + mediaType + ".");
        }

        Set<Integer> usedOrders = existing.stream()
                .map(MarketplaceListingMedia::getSortOrder)
                .collect(Collectors.toSet());
        List<Integer> availableOrders = java.util.stream.IntStream.range(0, MAX_FILES_PER_TYPE)
                .filter(order -> !usedOrders.contains(order))
                .boxed().toList();
        List<String> storedKeys = new ArrayList<>();
        List<MarketplaceListingMedia> created = new ArrayList<>();
        try {
            for (int fileIndex = 0; fileIndex < supplied.size(); fileIndex++) {
                MultipartFile file = supplied.get(fileIndex);
                DetectedFile detected = validateAndDetect(mediaType, file);
                String mediaId = "MEDIA-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
                String storageKey = listingId + "/" + mediaType.name().toLowerCase(Locale.ROOT)
                        + "/" + mediaId + detected.extension();
                Path temporary = Files.createTempFile("everysale-" + mediaId, ".upload");
                try {
                    try (InputStream input = file.getInputStream()) {
                        Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
                    }
                    long actualSize = Files.size(temporary);
                    validateStoredSize(mediaType, actualSize, file.getOriginalFilename());
                    mediaScanner.assertSafe(temporary, safeFilename(file.getOriginalFilename()), detected.contentType());
                    mediaStorage.store(storageKey, temporary, detected.contentType());
                    storedKeys.add(storageKey);

                    MarketplaceListingMedia media = MarketplaceListingMedia.builder()
                            .mediaId(mediaId)
                            .listingId(listingId)
                            .mediaType(mediaType)
                            .storageKey(storageKey.replace('\\', '/'))
                            .originalFilename(safeFilename(file.getOriginalFilename()))
                            .contentType(detected.contentType())
                            .sizeBytes(actualSize)
                            .sha256(sha256(temporary))
                            .sortOrder(availableOrders.get(fileIndex))
                            .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                            .build();
                    created.add(mediaRepository.save(media));
                } finally {
                    deleteStagingFile(temporary);
                }
            }

            if (mediaType == ListingMediaType.PRODUCT_IMAGE && existing.isEmpty()) {
                listing.setImageUrl(publicImageUrl(created.get(0).getMediaId()));
                listingRepository.save(listing);
            }
            deleteOnRollback(storedKeys);
            return created.stream().map(this::toOwnerResponse).toList();
        } catch (IOException | RuntimeException exception) {
            storedKeys.forEach(this::deleteStorageQuietly);
            if (exception instanceof IllegalArgumentException illegalArgumentException) {
                throw illegalArgumentException;
            }
            if (exception instanceof IllegalStateException illegalStateException) {
                throw illegalStateException;
            }
            throw new IllegalArgumentException("Failed to store listing media.", exception);
        }
    }

    @Transactional
    public void delete(String listingId, String mediaId) {
        MarketplaceListing listing = listingRepository.findByIdForUpdate(listingId)
                .orElseThrow(() -> new IllegalArgumentException("Listing not found: " + listingId));
        MarketplaceListingMedia media = mediaRepository.findByMediaIdAndListingId(mediaId, listingId)
                .orElseThrow(() -> new IllegalArgumentException("Listing media not found: " + mediaId));
        String storageKey = media.getStorageKey();
        MarketplaceListingMedia replacement = media.getMediaType() == ListingMediaType.PRODUCT_IMAGE
                ? mediaRepository.findByListingIdAndMediaTypeOrderBySortOrderAsc(
                                listingId, ListingMediaType.PRODUCT_IMAGE)
                        .stream()
                        .filter(candidate -> !candidate.getMediaId().equals(mediaId))
                        .findFirst()
                        .orElse(null)
                : null;
        mediaRepository.delete(media);

        if (media.getMediaType() == ListingMediaType.PRODUCT_IMAGE
                && publicImageUrl(mediaId).equals(listing.getImageUrl())) {
            listing.setImageUrl(replacement == null ? null : publicImageUrl(replacement.getMediaId()));
            listingRepository.save(listing);
        }
        deleteAfterCommit(storageKey);
    }

    @Transactional(readOnly = true)
    public StoredMedia loadPublicImage(String mediaId) {
        MarketplaceListingMedia media = mediaRepository.findById(mediaId)
                .filter(item -> item.getMediaType() == ListingMediaType.PRODUCT_IMAGE)
                .orElseThrow(() -> new IllegalArgumentException("Product image not found: " + mediaId));
        requirePublicListing(media.getListingId());
        return storedMedia(media);
    }

    @Transactional(readOnly = true)
    public StoredMedia loadOwnedMedia(String listingId, String mediaId) {
        MarketplaceListingMedia media = mediaRepository.findByMediaIdAndListingId(mediaId, listingId)
                .orElseThrow(() -> new IllegalArgumentException("Listing media not found: " + mediaId));
        return storedMedia(media);
    }

    private MarketplaceListing requireListing(String listingId) {
        return listingRepository.findById(listingId)
                .orElseThrow(() -> new IllegalArgumentException("Listing not found: " + listingId));
    }

    private MarketplaceListing requirePublicListing(String listingId) {
        MarketplaceListing listing = requireListing(listingId);
        if (listing.getStatus() != com.example.payment.domain.model.marketplace.ListingStatus.ACTIVE
                && listing.getStatus() != com.example.payment.domain.model.marketplace.ListingStatus.PAUSED
                && listing.getStatus() != com.example.payment.domain.model.marketplace.ListingStatus.ENDED) {
            throw new IllegalArgumentException("Public listing image not found: " + listingId);
        }
        return listing;
    }

    private StoredMedia storedMedia(MarketplaceListingMedia media) {
        Resource resource = mediaStorage.load(media.getStorageKey());
        return new StoredMedia(resource, media.getContentType(), media.getOriginalFilename(), media.getSizeBytes());
    }

    private ListingMediaResponse toOwnerResponse(MarketplaceListingMedia media) {
        return toResponse(media, "/api/c2c/listings/" + media.getListingId()
                + "/media/" + media.getMediaId() + "/content");
    }

    private ListingMediaResponse toPublicResponse(MarketplaceListingMedia media) {
        return toResponse(media, publicImageUrl(media.getMediaId()));
    }

    private ListingMediaResponse toResponse(MarketplaceListingMedia media, String url) {
        return ListingMediaResponse.builder()
                .mediaId(media.getMediaId())
                .listingId(media.getListingId())
                .mediaType(media.getMediaType())
                .fileName(media.getOriginalFilename())
                .contentType(media.getContentType())
                .sizeBytes(media.getSizeBytes())
                .sortOrder(media.getSortOrder())
                .url(url)
                .build();
    }

    private DetectedFile validateAndDetect(ListingMediaType mediaType, MultipartFile file) {
        long limit = mediaType == ListingMediaType.PRODUCT_IMAGE ? MAX_IMAGE_BYTES : MAX_EVIDENCE_BYTES;
        if (file.getSize() <= 0 || file.getSize() > limit) {
            throw new IllegalArgumentException("Media file size is outside the allowed range: " + safeFilename(file.getOriginalFilename()));
        }
        byte[] signature;
        try (InputStream input = file.getInputStream()) {
            signature = input.readNBytes(16);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed to inspect media file.", exception);
        }
        DetectedFile detected = detect(signature);
        if (detected == null || (mediaType == ListingMediaType.PRODUCT_IMAGE && "application/pdf".equals(detected.contentType()))) {
            throw new IllegalArgumentException("Unsupported media format. Use JPEG, PNG, WebP"
                    + (mediaType == ListingMediaType.AUTHENTICITY_EVIDENCE ? ", or PDF." : "."));
        }
        return detected;
    }

    private void validateStoredSize(ListingMediaType mediaType, long actualSize, String filename) {
        long limit = mediaType == ListingMediaType.PRODUCT_IMAGE ? MAX_IMAGE_BYTES : MAX_EVIDENCE_BYTES;
        if (actualSize <= 0 || actualSize > limit) {
            throw new IllegalArgumentException("Media file size is outside the allowed range: " + safeFilename(filename));
        }
    }

    private DetectedFile detect(byte[] bytes) {
        if (startsWith(bytes, new int[]{0xFF, 0xD8, 0xFF})) return new DetectedFile("image/jpeg", ".jpg");
        if (startsWith(bytes, new int[]{0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A})) return new DetectedFile("image/png", ".png");
        if (bytes.length >= 12 && startsWith(bytes, new int[]{'R', 'I', 'F', 'F'})
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return new DetectedFile("image/webp", ".webp");
        }
        if (startsWith(bytes, new int[]{'%', 'P', 'D', 'F', '-'})) return new DetectedFile("application/pdf", ".pdf");
        return null;
    }

    private boolean startsWith(byte[] bytes, int[] expected) {
        if (bytes.length < expected.length) return false;
        for (int index = 0; index < expected.length; index++) {
            if ((bytes[index] & 0xFF) != (expected[index] & 0xFF)) return false;
        }
        return true;
    }

    private String safeFilename(String filename) {
        String value = filename == null || filename.isBlank() ? "upload" : Path.of(filename).getFileName().toString();
        return value.replaceAll("[\\p{Cntrl}]", "_").substring(0, Math.min(value.length(), 255));
    }

    private String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException exception) {
            throw new IllegalArgumentException("Failed to hash listing media.", exception);
        }
    }

    private void deleteStagingFile(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The OS temp directory can be reconciled independently from durable media storage.
        }
    }

    private void deleteStorageQuietly(String storageKey) {
        try {
            mediaStorage.delete(storageKey);
        } catch (RuntimeException ignored) {
            // DB state remains authoritative; storage lifecycle rules handle orphaned objects.
        }
    }

    private void deleteOnRollback(List<String> storageKeys) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        List<String> storedObjects = List.copyOf(storageKeys);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    storedObjects.forEach(ListingMediaService.this::deleteStorageQuietly);
                }
            }
        });
    }

    private void deleteAfterCommit(String storageKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deleteStorageQuietly(storageKey);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteStorageQuietly(storageKey);
            }
        });
    }

    private String publicImageUrl(String mediaId) {
        return "/api/media/product-images/" + mediaId;
    }

    private record DetectedFile(String contentType, String extension) {
    }

    public record StoredMedia(Resource resource, String contentType, String filename, long sizeBytes) {
    }
}

package com.example.payment.application.service;

import com.example.payment.domain.model.marketplace.ListingMediaType;
import com.example.payment.domain.model.marketplace.MarketplaceListing;
import com.example.payment.domain.model.marketplace.MarketplaceListingMedia;
import com.example.payment.domain.repository.MarketplaceListingMediaRepository;
import com.example.payment.domain.repository.MarketplaceListingRepository;
import com.example.payment.infrastructure.storage.LocalListingMediaStorage;
import com.example.payment.infrastructure.storage.NoOpListingMediaScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ListingMediaServiceTest {

    private final MarketplaceListingRepository listingRepository = mock(MarketplaceListingRepository.class);
    private final MarketplaceListingMediaRepository mediaRepository = mock(MarketplaceListingMediaRepository.class);

    @TempDir
    Path storageRoot;

    @Test
    void storesUpToThreeVerifiedProductImagesAndSelectsTheFirstAsPrimary() {
        MarketplaceListing listing = listing();
        ListingMediaService service = service();
        when(listingRepository.findByIdForUpdate("LIST-1")).thenReturn(Optional.of(listing));
        when(mediaRepository.findByListingIdAndMediaTypeOrderBySortOrderAsc(
                "LIST-1", ListingMediaType.PRODUCT_IMAGE)).thenReturn(List.of());
        when(mediaRepository.save(any(MarketplaceListingMedia.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(listingRepository.save(any(MarketplaceListing.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        List<?> result = service.upload("LIST-1", ListingMediaType.PRODUCT_IMAGE, List.of(
                png("front.png"), png("side.png"), png("detail.png")
        ));

        assertEquals(3, result.size());
        assertTrue(listing.getImageUrl().startsWith("/api/media/product-images/MEDIA-"));
        verify(mediaRepository, org.mockito.Mockito.times(3)).save(any(MarketplaceListingMedia.class));
    }

    @Test
    void rejectsAFourthProductImageEvenAcrossSeparateRequests() {
        ListingMediaService service = service();
        when(listingRepository.findByIdForUpdate("LIST-1")).thenReturn(Optional.of(listing()));
        when(mediaRepository.findByListingIdAndMediaTypeOrderBySortOrderAsc(
                "LIST-1", ListingMediaType.PRODUCT_IMAGE)).thenReturn(List.of(
                media(0), media(1), media(2)
        ));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.upload("LIST-1", ListingMediaType.PRODUCT_IMAGE, List.of(png("fourth.png"))));

        assertTrue(exception.getMessage().contains("at most 3"));
        verify(mediaRepository, never()).save(any(MarketplaceListingMedia.class));
    }

    @Test
    void checksFileSignaturesInsteadOfTrustingTheBrowserContentType() {
        ListingMediaService service = service();
        when(listingRepository.findByIdForUpdate("LIST-1")).thenReturn(Optional.of(listing()));
        when(mediaRepository.findByListingIdAndMediaTypeOrderBySortOrderAsc(
                "LIST-1", ListingMediaType.PRODUCT_IMAGE)).thenReturn(List.of());
        MockMultipartFile disguised = new MockMultipartFile(
                "files", "fake.png", "image/png", "not-an-image".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThrows(IllegalArgumentException.class,
                () -> service.upload("LIST-1", ListingMediaType.PRODUCT_IMAGE, List.of(disguised)));
        verify(mediaRepository, never()).save(any(MarketplaceListingMedia.class));
    }

    @Test
    void acceptsPdfOnlyForAuthenticityEvidence() {
        ListingMediaService service = service();
        when(listingRepository.findByIdForUpdate("LIST-1")).thenReturn(Optional.of(listing()));
        when(mediaRepository.findByListingIdAndMediaTypeOrderBySortOrderAsc(
                "LIST-1", ListingMediaType.AUTHENTICITY_EVIDENCE)).thenReturn(List.of());
        when(mediaRepository.save(any(MarketplaceListingMedia.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        MockMultipartFile pdf = new MockMultipartFile(
                "files", "receipt.pdf", "application/pdf", "%PDF-1.7 evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertEquals(1, service.upload("LIST-1", ListingMediaType.AUTHENTICITY_EVIDENCE, List.of(pdf)).size());
    }

    @Test
    void ownerMediaUsesTheAuthenticatedContentEndpoint() {
        ListingMediaService service = service();
        MarketplaceListing listing = listing();
        MarketplaceListingMedia media = media(0);
        when(listingRepository.findById("LIST-1")).thenReturn(Optional.of(listing));
        when(mediaRepository.findByListingIdOrderByMediaTypeAscSortOrderAsc("LIST-1"))
                .thenReturn(List.of(media));

        var result = service.listForListing("LIST-1");

        assertEquals("/api/c2c/listings/LIST-1/media/MEDIA-0/content", result.get(0).getUrl());
    }

    @Test
    void draftListingImagesAreNotPublic() {
        ListingMediaService service = service();
        MarketplaceListing listing = listing();
        listing.setStatus(com.example.payment.domain.model.marketplace.ListingStatus.DRAFT);
        when(listingRepository.findById("LIST-1")).thenReturn(Optional.of(listing));

        assertThrows(IllegalArgumentException.class, () -> service.listPublicImages("LIST-1"));
    }

    @Test
    void scannerOutageFailsClosedWithoutWritingMetadata() {
        MarketplaceListing listing = listing();
        com.example.payment.application.service.ListingMediaScanner unavailableScanner =
                mock(com.example.payment.application.service.ListingMediaScanner.class);
        ListingMediaService service = new ListingMediaService(
                listingRepository,
                mediaRepository,
                new LocalListingMediaStorage(storageRoot.toString()),
                unavailableScanner);
        when(listingRepository.findByIdForUpdate("LIST-1")).thenReturn(Optional.of(listing));
        when(mediaRepository.findByListingIdAndMediaTypeOrderBySortOrderAsc(
                "LIST-1", ListingMediaType.PRODUCT_IMAGE)).thenReturn(List.of());
        org.mockito.Mockito.doThrow(new IllegalStateException("scanner offline"))
                .when(unavailableScanner).assertSafe(any(Path.class), any(String.class), any(String.class));

        assertThrows(IllegalStateException.class,
                () -> service.upload("LIST-1", ListingMediaType.PRODUCT_IMAGE, List.of(png("front.png"))));
        verify(mediaRepository, never()).save(any(MarketplaceListingMedia.class));
    }

    private ListingMediaService service() {
        return new ListingMediaService(
                listingRepository,
                mediaRepository,
                new LocalListingMediaStorage(storageRoot.toString()),
                new NoOpListingMediaScanner()
        );
    }

    private MarketplaceListing listing() {
        return MarketplaceListing.builder().listingId("LIST-1").sellerId("SELLER-1").productId("PROD-1").build();
    }

    private MarketplaceListingMedia media(int order) {
        return MarketplaceListingMedia.builder()
                .mediaId("MEDIA-" + order)
                .listingId("LIST-1")
                .mediaType(ListingMediaType.PRODUCT_IMAGE)
                .sortOrder(order)
                .build();
    }

    private MockMultipartFile png(String name) {
        byte[] bytes = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
        return new MockMultipartFile("files", name, "image/png", bytes);
    }
}

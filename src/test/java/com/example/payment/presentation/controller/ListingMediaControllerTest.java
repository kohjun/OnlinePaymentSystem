package com.example.payment.presentation.controller;

import com.example.payment.application.service.ListingMediaService;
import com.example.payment.domain.model.marketplace.ListingMediaType;
import com.example.payment.infrastructure.security.AuthorizationGuard;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ListingMediaControllerTest {

    private final ListingMediaService mediaService = mock(ListingMediaService.class);
    private final AuthorizationGuard authorizationGuard = mock(AuthorizationGuard.class);
    private final ListingMediaController controller = new ListingMediaController(mediaService, authorizationGuard);

    @Test
    void scannerOutageReturnsServiceUnavailableWithoutLeakingInfrastructureDetails() {
        when(mediaService.sellerIdForListing("LIST-1")).thenReturn("SELLER-1");
        doThrow(new IllegalStateException("clamav.internal:3310 refused connection"))
                .when(mediaService).upload(any(), any(), any());
        MockMultipartFile file = new MockMultipartFile(
                "files", "front.png", "image/png", new byte[]{1});

        var response = controller.upload("LIST-1", ListingMediaType.PRODUCT_IMAGE, List.of(file));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("Media security validation is temporarily unavailable. Please retry later.",
                ((java.util.Map<?, ?>) response.getBody()).get("message"));
    }

    @Test
    void privatePdfEvidenceIsDownloadedWithBrowserIsolationHeaders() {
        when(mediaService.sellerIdForListing("LIST-1")).thenReturn("SELLER-1");
        when(mediaService.loadOwnedMedia("LIST-1", "MEDIA-1")).thenReturn(
                new ListingMediaService.StoredMedia(
                        new ByteArrayResource("%PDF".getBytes()),
                        "application/pdf",
                        "receipt.pdf",
                        4));

        var response = controller.ownedContent("LIST-1", "MEDIA-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("attachment", response.getHeaders().getContentDisposition().getType());
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        assertEquals("sandbox", response.getHeaders().getFirst("Content-Security-Policy"));
    }
}

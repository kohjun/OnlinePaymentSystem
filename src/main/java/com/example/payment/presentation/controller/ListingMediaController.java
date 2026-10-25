package com.example.payment.presentation.controller;

import com.example.payment.application.service.ListingMediaService;
import com.example.payment.domain.model.marketplace.ListingMediaType;
import com.example.payment.infrastructure.security.AuthorizationGuard;
import com.example.payment.presentation.dto.response.ListingMediaResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class ListingMediaController {

    private final ListingMediaService listingMediaService;
    private final AuthorizationGuard authorizationGuard;

    @GetMapping("/api/media/listings/{listingId}/images")
    public ResponseEntity<?> publicImages(@PathVariable String listingId) {
        try {
            return ResponseEntity.ok(listingMediaService.listPublicImages(listingId));
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }

    @GetMapping("/api/media/product-images/{mediaId}")
    public ResponseEntity<?> productImage(@PathVariable String mediaId) {
        try {
            return mediaResponse(listingMediaService.loadPublicImage(mediaId), true);
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }

    @GetMapping("/api/c2c/listings/{listingId}/media")
    public ResponseEntity<?> listingMedia(@PathVariable String listingId) {
        try {
            authorizationGuard.requireSellerAccess(listingMediaService.sellerIdForListing(listingId));
            return ResponseEntity.ok(listingMediaService.listForListing(listingId));
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }

    @PostMapping(value = "/api/c2c/listings/{listingId}/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@PathVariable String listingId,
                                    @RequestParam ListingMediaType mediaType,
                                    @RequestParam("files") List<MultipartFile> files) {
        try {
            authorizationGuard.requireSellerAccess(listingMediaService.sellerIdForListing(listingId));
            List<ListingMediaResponse> uploaded = listingMediaService.upload(listingId, mediaType, files);
            return ResponseEntity.status(HttpStatus.CREATED).body(uploaded);
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (IllegalStateException exception) {
            return error(HttpStatus.SERVICE_UNAVAILABLE,
                    "Media security validation is temporarily unavailable. Please retry later.");
        }
    }

    @DeleteMapping("/api/c2c/listings/{listingId}/media/{mediaId}")
    public ResponseEntity<?> delete(@PathVariable String listingId, @PathVariable String mediaId) {
        try {
            authorizationGuard.requireSellerAccess(listingMediaService.sellerIdForListing(listingId));
            listingMediaService.delete(listingId, mediaId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }

    @GetMapping("/api/c2c/listings/{listingId}/media/{mediaId}/content")
    public ResponseEntity<?> ownedContent(@PathVariable String listingId, @PathVariable String mediaId) {
        try {
            authorizationGuard.requireSellerAccess(listingMediaService.sellerIdForListing(listingId));
            return mediaResponse(listingMediaService.loadOwnedMedia(listingId, mediaId), false);
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }

    private ResponseEntity<Resource> mediaResponse(ListingMediaService.StoredMedia media, boolean publicImage) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(media.contentType()));
        headers.setContentLength(media.sizeBytes());
        ContentDisposition.Builder disposition = media.contentType().startsWith("image/")
                ? ContentDisposition.inline()
                : ContentDisposition.attachment();
        headers.setContentDisposition(disposition.filename(media.filename(), StandardCharsets.UTF_8).build());
        headers.setCacheControl(publicImage
                ? CacheControl.maxAge(Duration.ofDays(30)).cachePublic()
                : CacheControl.noStore());
        headers.set("X-Content-Type-Options", "nosniff");
        if (!media.contentType().startsWith("image/")) {
            headers.set("Content-Security-Policy", "sandbox");
        }
        return new ResponseEntity<>(media.resource(), headers, HttpStatus.OK);
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("status", "FAILED", "message", message));
    }
}

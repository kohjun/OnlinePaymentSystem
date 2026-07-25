package com.example.payment.presentation.controller;

import com.example.payment.application.service.SellerMarketplaceService;
import com.example.payment.infrastructure.security.AuthorizationGuard;
import com.example.payment.infrastructure.security.EverySalePrincipal;
import com.example.payment.presentation.dto.request.CreateSaleEventRequest;
import com.example.payment.presentation.dto.request.CreateSellerListingRequest;
import com.example.payment.presentation.dto.request.SubmitSellerVerificationRequest;
import com.example.payment.presentation.dto.request.UpdateC2CListingRequest;
import com.example.payment.presentation.dto.response.SellerListingResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/c2c")
@RequiredArgsConstructor
@Validated
@Slf4j
public class C2CListingController {

    private final AuthorizationGuard authorizationGuard;
    private final SellerMarketplaceService sellerMarketplaceService;

    @PostMapping("/seller/verification")
    public ResponseEntity<?> submitSellerVerification(@Valid @RequestBody SubmitSellerVerificationRequest request) {
        try {
            EverySalePrincipal principal = authorizationGuard.currentPrincipal();
            return ResponseEntity.ok(sellerMarketplaceService.submitSellerVerification(
                    principal.userId(),
                    principal.customerId(),
                    request
            ));
        } catch (IllegalArgumentException e) {
            log.warn("C2C seller verification submission rejected: {}", e.getMessage());
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/listings")
    public ResponseEntity<?> createDraftListing(@Valid @RequestBody CreateSellerListingRequest request) {
        try {
            EverySalePrincipal principal = authorizationGuard.currentPrincipal();
            SellerListingResponse response = sellerMarketplaceService.createDraftListingForOwner(
                    principal.userId(),
                    principal.customerId(),
                    request
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException e) {
            log.warn("C2C draft listing creation rejected: {}", e.getMessage());
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/seller/listings")
    public ResponseEntity<?> getMyListings() {
        try {
            EverySalePrincipal principal = authorizationGuard.currentPrincipal();
            return ResponseEntity.ok(sellerMarketplaceService.getListingsByOwner(
                    principal.userId(),
                    principal.customerId()
            ));
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PatchMapping("/listings/{listingId}")
    public ResponseEntity<?> updateDraftListing(
            @PathVariable String listingId,
            @Valid @RequestBody UpdateC2CListingRequest request) {
        try {
            EverySalePrincipal principal = authorizationGuard.currentPrincipal();
            return ResponseEntity.ok(sellerMarketplaceService.updateDraftListingForOwner(
                    principal.userId(),
                    principal.customerId(),
                    listingId,
                    request
            ));
        } catch (IllegalArgumentException e) {
            log.warn("C2C listing update rejected: {}", e.getMessage());
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/listings/{listingId}/submit")
    public ResponseEntity<?> submitListingForReview(@PathVariable String listingId) {
        try {
            EverySalePrincipal principal = authorizationGuard.currentPrincipal();
            return ResponseEntity.ok(sellerMarketplaceService.submitListingForReview(
                    principal.userId(),
                    principal.customerId(),
                    listingId
            ));
        } catch (IllegalArgumentException e) {
            log.warn("C2C listing review submission rejected: {}", e.getMessage());
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/listings/{listingId}/sale-events")
    public ResponseEntity<?> createSaleEvent(
            @PathVariable String listingId,
            @Valid @RequestBody CreateSaleEventRequest request) {
        try {
            EverySalePrincipal principal = authorizationGuard.currentPrincipal();
            SellerListingResponse response = sellerMarketplaceService.createSaleEventForOwner(
                    principal.userId(),
                    principal.customerId(),
                    listingId,
                    request
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException e) {
            log.warn("C2C sale event creation rejected: {}", e.getMessage());
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/sale-events/{eventId}/publish")
    public ResponseEntity<?> publishSaleEvent(@PathVariable String eventId) {
        try {
            EverySalePrincipal principal = authorizationGuard.currentPrincipal();
            return ResponseEntity.ok(sellerMarketplaceService.publishSaleEventForOwner(
                    principal.userId(),
                    principal.customerId(),
                    eventId
            ));
        } catch (IllegalArgumentException e) {
            log.warn("C2C sale event publish rejected: {}", e.getMessage());
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "status", "FAILED",
                "message", message
        ));
    }
}

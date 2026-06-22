package com.example.payment.presentation.controller;

import com.example.payment.application.service.MarketplaceOrderService;
import com.example.payment.application.service.SellerMarketplaceService;
import com.example.payment.application.service.SellerPayoutService;
import com.example.payment.domain.model.marketplace.ListingStatus;
import com.example.payment.domain.model.marketplace.SellerPayoutStatus;
import com.example.payment.presentation.dto.request.CreateSaleEventRequest;
import com.example.payment.presentation.dto.request.CreateSellerListingRequest;
import com.example.payment.presentation.dto.request.CreateSellerRequest;
import com.example.payment.presentation.dto.request.ReviewListingRequest;
import com.example.payment.presentation.dto.request.UpdateFulfillmentRequest;
import com.example.payment.presentation.dto.response.SellerListingResponse;
import com.example.payment.presentation.dto.response.SellerResponse;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sellers")
@RequiredArgsConstructor
@Validated
@Slf4j
public class SellerController {

    private final SellerMarketplaceService sellerMarketplaceService;
    private final MarketplaceOrderService marketplaceOrderService;
    private final SellerPayoutService sellerPayoutService;

    @PostMapping
    public ResponseEntity<?> createSeller(@Valid @RequestBody CreateSellerRequest request) {
        SellerResponse response = sellerMarketplaceService.createSeller(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{sellerId}")
    public ResponseEntity<?> getSeller(@PathVariable String sellerId) {
        try {
            return ResponseEntity.ok(sellerMarketplaceService.getSeller(sellerId));
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @PostMapping("/{sellerId}/listings")
    public ResponseEntity<?> createListing(
            @PathVariable String sellerId,
            @Valid @RequestBody CreateSellerListingRequest request) {
        try {
            SellerListingResponse response = sellerMarketplaceService.createListing(sellerId, request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException e) {
            log.warn("Seller listing creation rejected: {}", e.getMessage());
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/{sellerId}/listings")
    public ResponseEntity<?> getListings(@PathVariable String sellerId) {
        try {
            List<SellerListingResponse> response = sellerMarketplaceService.getListings(sellerId);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @GetMapping("/{sellerId}/orders")
    public ResponseEntity<?> getOrders(@PathVariable String sellerId) {
        try {
            sellerMarketplaceService.getSeller(sellerId);
            return ResponseEntity.ok(marketplaceOrderService.getSellerOrders(sellerId));
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @PatchMapping("/{sellerId}/orders/{marketplaceOrderId}/fulfillment")
    public ResponseEntity<?> updateFulfillment(
            @PathVariable String sellerId,
            @PathVariable String marketplaceOrderId,
            @Valid @RequestBody UpdateFulfillmentRequest request) {
        try {
            return ResponseEntity.ok(marketplaceOrderService.updateFulfillment(
                    sellerId,
                    marketplaceOrderId,
                    request.getFulfillmentStatus()
            ));
        } catch (IllegalArgumentException e) {
            log.warn("Marketplace order fulfillment update rejected: {}", e.getMessage());
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/{sellerId}/payouts")
    public ResponseEntity<?> getPayouts(
            @PathVariable String sellerId,
            @RequestParam(required = false) String status) {
        try {
            sellerMarketplaceService.getSeller(sellerId);
            SellerPayoutStatus payoutStatus = status == null || status.isBlank()
                    ? null
                    : SellerPayoutStatus.valueOf(status.trim().toUpperCase());
            return ResponseEntity.ok(sellerPayoutService.getSellerPayouts(sellerId, payoutStatus));
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/{sellerId}/payouts/{payoutId}/release")
    public ResponseEntity<?> releasePayout(
            @PathVariable String sellerId,
            @PathVariable String payoutId) {
        try {
            sellerMarketplaceService.getSeller(sellerId);
            return ResponseEntity.ok(sellerPayoutService.releasePayout(sellerId, payoutId));
        } catch (IllegalArgumentException e) {
            log.warn("Seller payout release rejected: {}", e.getMessage());
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/{sellerId}/listings/{listingId}/sale-events")
    public ResponseEntity<?> createSaleEvent(
            @PathVariable String sellerId,
            @PathVariable String listingId,
            @Valid @RequestBody CreateSaleEventRequest request) {
        try {
            SellerListingResponse response = sellerMarketplaceService.createSaleEvent(sellerId, listingId, request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException e) {
            log.warn("Sale event creation rejected: {}", e.getMessage());
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/moderation/listings")
    public ResponseEntity<?> getListingsForReview(@RequestParam(required = false) String status) {
        try {
            ListingStatus listingStatus = status == null || status.isBlank()
                    ? ListingStatus.PENDING_REVIEW
                    : ListingStatus.valueOf(status.trim().toUpperCase());
            return ResponseEntity.ok(sellerMarketplaceService.getListingsForReview(listingStatus));
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/moderation/listings/{listingId}/approve")
    public ResponseEntity<?> approveListing(
            @PathVariable String listingId,
            @Valid @RequestBody ReviewListingRequest request) {
        try {
            return ResponseEntity.ok(sellerMarketplaceService.approveListing(listingId, request));
        } catch (IllegalArgumentException e) {
            log.warn("Listing approval rejected: {}", e.getMessage());
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/moderation/listings/{listingId}/reject")
    public ResponseEntity<?> rejectListing(
            @PathVariable String listingId,
            @Valid @RequestBody ReviewListingRequest request) {
        try {
            return ResponseEntity.ok(sellerMarketplaceService.rejectListing(listingId, request));
        } catch (IllegalArgumentException e) {
            log.warn("Listing rejection rejected: {}", e.getMessage());
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/{sellerId}/sale-events/{eventId}/publish")
    public ResponseEntity<?> publishSaleEvent(
            @PathVariable String sellerId,
            @PathVariable String eventId) {
        try {
            return ResponseEntity.ok(sellerMarketplaceService.publishSaleEvent(sellerId, eventId));
        } catch (IllegalArgumentException e) {
            log.warn("Sale event publish rejected: {}", e.getMessage());
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

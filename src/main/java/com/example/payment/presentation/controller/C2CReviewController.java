package com.example.payment.presentation.controller;

import com.example.payment.application.service.MarketplaceReviewService;
import com.example.payment.infrastructure.security.AuthorizationGuard;
import com.example.payment.infrastructure.security.EverySalePrincipal;
import com.example.payment.presentation.dto.request.CreateMarketplaceReviewRequest;
import com.example.payment.presentation.dto.request.ModerateMarketplaceReviewRequest;
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
public class C2CReviewController {

    private final AuthorizationGuard authorizationGuard;
    private final MarketplaceReviewService reviewService;

    @PostMapping("/orders/{marketplaceOrderId}/seller-review")
    public ResponseEntity<?> upsertSellerReview(
            @PathVariable String marketplaceOrderId,
            @Valid @RequestBody CreateMarketplaceReviewRequest request) {
        try {
            EverySalePrincipal principal = authorizationGuard.currentPrincipal();
            return ResponseEntity.ok(reviewService.upsertSellerReview(principal, marketplaceOrderId, request));
        } catch (IllegalArgumentException e) {
            log.warn("C2C seller review rejected: {}", e.getMessage());
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/sellers/{sellerId}/reviews")
    public ResponseEntity<?> getSellerReviews(@PathVariable String sellerId) {
        return ResponseEntity.ok(reviewService.getVisibleSellerReviews(sellerId));
    }

    @PatchMapping("/moderation/reviews/{reviewId}")
    public ResponseEntity<?> moderateReview(
            @PathVariable String reviewId,
            @Valid @RequestBody ModerateMarketplaceReviewRequest request) {
        try {
            authorizationGuard.requireAdmin();
            EverySalePrincipal principal = authorizationGuard.currentPrincipal();
            return ResponseEntity.ok(reviewService.moderateReview(principal.userId(), reviewId, request));
        } catch (IllegalArgumentException e) {
            log.warn("C2C review moderation rejected: {}", e.getMessage());
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

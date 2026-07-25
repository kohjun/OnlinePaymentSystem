package com.example.payment.application.service;

import com.example.payment.domain.model.marketplace.FulfillmentStatus;
import com.example.payment.domain.model.marketplace.MarketplaceOrder;
import com.example.payment.domain.model.marketplace.MarketplaceOrderStatus;
import com.example.payment.domain.model.marketplace.MarketplaceReview;
import com.example.payment.domain.model.marketplace.ReviewStatus;
import com.example.payment.domain.repository.MarketplaceOrderRepository;
import com.example.payment.domain.repository.MarketplaceReviewRepository;
import com.example.payment.infrastructure.security.EverySalePrincipal;
import com.example.payment.presentation.dto.request.CreateMarketplaceReviewRequest;
import com.example.payment.presentation.dto.request.ModerateMarketplaceReviewRequest;
import com.example.payment.presentation.dto.response.MarketplaceReviewResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MarketplaceReviewService {

    private final MarketplaceOrderRepository orderRepository;
    private final MarketplaceReviewRepository reviewRepository;

    @Transactional
    public MarketplaceReviewResponse upsertSellerReview(EverySalePrincipal principal,
                                                        String marketplaceOrderId,
                                                        CreateMarketplaceReviewRequest request) {
        String reviewerUserId = requireText(principal.userId(), "Authenticated user id is required.");
        String reviewerCustomerId = requireText(principal.customerId(), "Authenticated customer id is required.");
        MarketplaceOrder order = orderRepository.findById(marketplaceOrderId)
                .orElseThrow(() -> new IllegalArgumentException("Marketplace order not found: " + marketplaceOrderId));
        validateReviewableOrder(order, reviewerCustomerId);

        MarketplaceReview review = reviewRepository
                .findByMarketplaceOrderIdAndReviewerUserId(marketplaceOrderId, reviewerUserId)
                .orElseGet(() -> MarketplaceReview.builder()
                        .reviewId("REV-" + shortId())
                        .marketplaceOrderId(marketplaceOrderId)
                        .reviewerUserId(reviewerUserId)
                        .reviewerCustomerId(reviewerCustomerId)
                        .targetSellerId(order.getSellerId())
                        .status(ReviewStatus.VISIBLE)
                        .createdAt(LocalDateTime.now())
                        .build());
        review.setRating(request.getRating());
        review.setComment(trimToNull(request.getComment()));
        return toResponse(reviewRepository.save(review));
    }

    @Transactional(readOnly = true)
    public List<MarketplaceReviewResponse> getVisibleSellerReviews(String sellerId) {
        return reviewRepository.findByTargetSellerIdAndStatusOrderByCreatedAtDesc(sellerId, ReviewStatus.VISIBLE)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public MarketplaceReviewResponse moderateReview(String operatorId,
                                                    String reviewId,
                                                    ModerateMarketplaceReviewRequest request) {
        MarketplaceReview review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new IllegalArgumentException("Marketplace review not found: " + reviewId));
        review.setStatus(request.getStatus());
        review.setModeratedBy(requireText(operatorId, "Operator id is required."));
        review.setModerationNote(trimToNull(request.getNote()));
        return toResponse(reviewRepository.save(review));
    }

    private void validateReviewableOrder(MarketplaceOrder order, String reviewerCustomerId) {
        if (!reviewerCustomerId.equals(order.getCustomerId())) {
            throw new IllegalArgumentException("Only the buyer of the marketplace order can review it.");
        }
        if (order.getStatus() != MarketplaceOrderStatus.PAID) {
            throw new IllegalArgumentException("Only paid marketplace orders can be reviewed.");
        }
        if (order.getFulfillmentStatus() != FulfillmentStatus.DELIVERED) {
            throw new IllegalArgumentException("Marketplace order must be delivered before review.");
        }
    }

    private MarketplaceReviewResponse toResponse(MarketplaceReview review) {
        return MarketplaceReviewResponse.builder()
                .reviewId(review.getReviewId())
                .marketplaceOrderId(review.getMarketplaceOrderId())
                .reviewerUserId(review.getReviewerUserId())
                .reviewerCustomerId(review.getReviewerCustomerId())
                .targetSellerId(review.getTargetSellerId())
                .rating(review.getRating())
                .comment(review.getComment())
                .status(review.getStatus())
                .moderatedBy(review.getModeratedBy())
                .moderationNote(review.getModerationNote())
                .createdAt(review.getCreatedAt())
                .updatedAt(review.getUpdatedAt())
                .build();
    }

    private String requireText(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }
}

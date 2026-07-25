package com.example.payment.presentation.dto.response;

import com.example.payment.domain.model.marketplace.ReviewStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class MarketplaceReviewResponse {
    private String reviewId;
    private String marketplaceOrderId;
    private String reviewerUserId;
    private String reviewerCustomerId;
    private String targetSellerId;
    private Integer rating;
    private String comment;
    private ReviewStatus status;
    private String moderatedBy;
    private String moderationNote;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

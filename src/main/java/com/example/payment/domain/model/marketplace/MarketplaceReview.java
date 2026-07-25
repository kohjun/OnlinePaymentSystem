package com.example.payment.domain.model.marketplace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "marketplace_reviews")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketplaceReview {

    @Id
    @Column(name = "review_id")
    private String reviewId;

    @Column(name = "marketplace_order_id", nullable = false)
    private String marketplaceOrderId;

    @Column(name = "reviewer_user_id", nullable = false)
    private String reviewerUserId;

    @Column(name = "reviewer_customer_id", nullable = false)
    private String reviewerCustomerId;

    @Column(name = "target_seller_id", nullable = false)
    private String targetSellerId;

    @Column(nullable = false)
    private Integer rating;

    @Column(length = 2000)
    private String comment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReviewStatus status;

    @Column(name = "moderated_by")
    private String moderatedBy;

    @Column(name = "moderation_note", length = 2000)
    private String moderationNote;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (status == null) {
            status = ReviewStatus.VISIBLE;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

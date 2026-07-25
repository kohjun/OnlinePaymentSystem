package com.example.payment.domain.repository;

import com.example.payment.domain.model.marketplace.MarketplaceReview;
import com.example.payment.domain.model.marketplace.ReviewStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MarketplaceReviewRepository extends JpaRepository<MarketplaceReview, String> {
    Optional<MarketplaceReview> findByMarketplaceOrderIdAndReviewerUserId(String marketplaceOrderId, String reviewerUserId);

    List<MarketplaceReview> findByTargetSellerIdAndStatusOrderByCreatedAtDesc(String targetSellerId, ReviewStatus status);
}

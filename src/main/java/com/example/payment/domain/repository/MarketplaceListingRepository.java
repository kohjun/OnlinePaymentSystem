package com.example.payment.domain.repository;

import com.example.payment.domain.model.marketplace.ListingStatus;
import com.example.payment.domain.model.marketplace.MarketplaceListing;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MarketplaceListingRepository extends JpaRepository<MarketplaceListing, String> {
    List<MarketplaceListing> findBySellerIdOrderByCreatedAtDesc(String sellerId);

    List<MarketplaceListing> findByStatus(ListingStatus status);

    List<MarketplaceListing> findByStatusOrderByCreatedAtAsc(ListingStatus status);
}

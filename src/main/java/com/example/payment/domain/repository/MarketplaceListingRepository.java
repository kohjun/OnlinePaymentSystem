package com.example.payment.domain.repository;

import com.example.payment.domain.model.marketplace.ListingStatus;
import com.example.payment.domain.model.marketplace.MarketplaceListing;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

import java.util.List;

@Repository
public interface MarketplaceListingRepository extends JpaRepository<MarketplaceListing, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select listing from MarketplaceListing listing where listing.listingId = :listingId")
    java.util.Optional<MarketplaceListing> findByIdForUpdate(@Param("listingId") String listingId);

    List<MarketplaceListing> findBySellerIdOrderByCreatedAtDesc(String sellerId);

    boolean existsByProductId(String productId);

    List<MarketplaceListing> findByStatus(ListingStatus status);

    List<MarketplaceListing> findByStatusOrderByCreatedAtAsc(ListingStatus status);

    long countByStatus(ListingStatus status);

    List<MarketplaceListing> findByStatusOrderByCreatedAtAsc(ListingStatus status, Pageable pageable);
}

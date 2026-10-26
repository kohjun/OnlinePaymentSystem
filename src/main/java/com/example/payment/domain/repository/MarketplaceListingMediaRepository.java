package com.example.payment.domain.repository;

import com.example.payment.domain.model.marketplace.ListingMediaType;
import com.example.payment.domain.model.marketplace.MarketplaceListingMedia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MarketplaceListingMediaRepository extends JpaRepository<MarketplaceListingMedia, String> {
    List<MarketplaceListingMedia> findByListingIdOrderByMediaTypeAscSortOrderAsc(String listingId);

    List<MarketplaceListingMedia> findByListingIdAndMediaTypeOrderBySortOrderAsc(
            String listingId,
            ListingMediaType mediaType
    );

    Optional<MarketplaceListingMedia> findByMediaIdAndListingId(String mediaId, String listingId);
}

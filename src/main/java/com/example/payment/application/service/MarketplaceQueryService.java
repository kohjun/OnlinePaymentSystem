package com.example.payment.application.service;

import com.example.payment.domain.model.inventory.Inventory;
import com.example.payment.domain.model.inventory.Product;
import com.example.payment.domain.model.marketplace.ListingStatus;
import com.example.payment.domain.model.marketplace.MarketplaceListing;
import com.example.payment.domain.model.marketplace.SaleEvent;
import com.example.payment.domain.model.marketplace.SaleEventStatus;
import com.example.payment.domain.model.marketplace.SaleType;
import com.example.payment.domain.model.marketplace.SellerProfile;
import com.example.payment.domain.repository.InventoryRepository;
import com.example.payment.domain.repository.MarketplaceListingRepository;
import com.example.payment.domain.repository.ProductRepository;
import com.example.payment.domain.repository.SaleEventRepository;
import com.example.payment.domain.repository.SellerProfileRepository;
import com.example.payment.presentation.dto.response.MarketplaceEventResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MarketplaceQueryService {

    private final SaleEventRepository saleEventRepository;
    private final MarketplaceListingRepository marketplaceListingRepository;
    private final SellerProfileRepository sellerProfileRepository;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;

    public List<MarketplaceEventResponse> getEvents(String status, String saleType, String keyword, String sort) {
        SaleEventStatus statusFilter = parseOptionalEnum(SaleEventStatus.class, status, "status");
        SaleType saleTypeFilter = parseOptionalEnum(SaleType.class, saleType, "saleType");

        List<SaleEventStatus> statuses = statusFilter == null
                ? List.of(SaleEventStatus.LIVE, SaleEventStatus.SCHEDULED)
                : List.of(statusFilter);

        List<MarketplaceEventResponse> responses = saleEventRepository.findByStatusInOrderByStartsAtAsc(statuses)
                .stream()
                .filter(event -> saleTypeFilter == null || event.getSaleType() == saleTypeFilter)
                .map(this::toResponse)
                .flatMap(Optional::stream)
                .filter(response -> matchesKeyword(response, keyword))
                .toList();

        return responses.stream()
                .sorted(comparator(sort))
                .toList();
    }

    public Optional<MarketplaceEventResponse> getEvent(String saleEventId) {
        return saleEventRepository.findById(saleEventId).flatMap(this::toResponse);
    }

    private Optional<MarketplaceEventResponse> toResponse(SaleEvent event) {
        Optional<MarketplaceListing> listingOpt = marketplaceListingRepository.findById(event.getListingId());
        if (listingOpt.isEmpty() || listingOpt.get().getStatus() != ListingStatus.ACTIVE) {
            return Optional.empty();
        }

        MarketplaceListing listing = listingOpt.get();
        Optional<Product> productOpt = productRepository.findById(event.getProductId());
        Optional<SellerProfile> sellerOpt = sellerProfileRepository.findById(event.getSellerId());
        Optional<Inventory> inventoryOpt = inventoryRepository.findById(event.getProductId());

        String title = notBlank(listing.getTitle())
                ? listing.getTitle()
                : productOpt.map(Product::getName).orElse(event.getProductId());
        String description = notBlank(listing.getDescription())
                ? listing.getDescription()
                : productOpt.map(Product::getDescription).orElse("");

        int totalQuantity = inventoryOpt.map(Inventory::getTotalQuantity).orElse(event.getStockQuantity());
        int availableQuantity = inventoryOpt.map(Inventory::getAvailableQuantity).orElse(event.getStockQuantity());

        return Optional.of(MarketplaceEventResponse.builder()
                .saleEventId(event.getSaleEventId())
                .listingId(event.getListingId())
                .sellerId(event.getSellerId())
                .sellerName(sellerOpt.map(SellerProfile::getDisplayName).orElse("EverySale Partner"))
                .productId(event.getProductId())
                .title(title)
                .description(description)
                .imageUrl(listing.getImageUrl())
                .category(productOpt.map(Product::getCategory).orElse(""))
                .saleType(event.getSaleType())
                .status(event.getStatus())
                .price(event.getPrice())
                .currency("KRW")
                .totalQuantity(totalQuantity)
                .availableQuantity(availableQuantity)
                .minBidIncrement(event.getMinBidIncrement())
                .reservePrice(event.getReservePrice())
                .startsAt(event.getStartsAt())
                .endsAt(event.getEndsAt())
                .build());
    }

    private boolean matchesKeyword(MarketplaceEventResponse response, String keyword) {
        if (!notBlank(keyword)) {
            return true;
        }
        String normalized = keyword.trim().toLowerCase(Locale.ROOT);
        return contains(response.getTitle(), normalized)
                || contains(response.getDescription(), normalized)
                || contains(response.getSellerName(), normalized)
                || contains(response.getProductId(), normalized);
    }

    private boolean contains(String value, String normalizedKeyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(normalizedKeyword);
    }

    private Comparator<MarketplaceEventResponse> comparator(String sort) {
        String normalized = notBlank(sort) ? sort.trim().toLowerCase(Locale.ROOT) : "startsat";
        return switch (normalized) {
            case "endingsoon" -> Comparator.comparing(
                    MarketplaceEventResponse::getEndsAt,
                    Comparator.nullsLast(Comparator.naturalOrder())
            );
            case "priceasc" -> Comparator.comparing(MarketplaceEventResponse::getPrice);
            case "pricedesc" -> Comparator.comparing(MarketplaceEventResponse::getPrice).reversed();
            default -> Comparator.comparing(
                    MarketplaceEventResponse::getStartsAt,
                    Comparator.nullsLast(Comparator.naturalOrder())
            ).thenComparing(MarketplaceEventResponse::getSaleEventId);
        };
    }

    private <T extends Enum<T>> T parseOptionalEnum(Class<T> type, String value, String fieldName) {
        if (!notBlank(value)) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid " + fieldName + ": " + value);
        }
    }

    private boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}

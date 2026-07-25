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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MarketplaceQueryService {

    private final SaleEventRepository saleEventRepository;
    private final MarketplaceListingRepository marketplaceListingRepository;
    private final SellerProfileRepository sellerProfileRepository;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;

    public List<MarketplaceEventResponse> getEvents(String status, String saleType, String keyword, String sort) {
        return getEventsPage(status, saleType, keyword, sort, 0, 100).getContent();
    }

    public Page<MarketplaceEventResponse> getEventsPage(String status,
                                                        String saleType,
                                                        String keyword,
                                                        String sort,
                                                        int page,
                                                        int size) {
        SaleEventStatus statusFilter = parseOptionalEnum(SaleEventStatus.class, status, "status");
        SaleType saleTypeFilter = parseOptionalEnum(SaleType.class, saleType, "saleType");

        List<SaleEventStatus> statuses = statusFilter == null
                ? List.of(SaleEventStatus.LIVE, SaleEventStatus.SCHEDULED)
                : List.of(statusFilter);

        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        String keywordFilter = notBlank(keyword)
                ? "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%"
                : null;
        Page<SaleEvent> events = saleEventRepository.searchPublicEvents(
                statuses,
                saleTypeFilter,
                keywordFilter,
                PageRequest.of(safePage, safeSize, eventSort(sort))
        );

        Map<String, MarketplaceListing> listings = marketplaceListingRepository
                .findAllById(events.stream().map(SaleEvent::getListingId).distinct().toList())
                .stream().collect(Collectors.toMap(MarketplaceListing::getListingId, Function.identity()));
        Map<String, Product> products = productRepository
                .findAllById(events.stream().map(SaleEvent::getProductId).distinct().toList())
                .stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<String, SellerProfile> sellers = sellerProfileRepository
                .findAllById(events.stream().map(SaleEvent::getSellerId).distinct().toList())
                .stream().collect(Collectors.toMap(SellerProfile::getSellerId, Function.identity()));
        Map<String, Inventory> inventories = inventoryRepository
                .findAllById(events.stream().map(SaleEvent::getProductId).distinct().toList())
                .stream().collect(Collectors.toMap(Inventory::getProductId, Function.identity()));

        List<MarketplaceEventResponse> content = events.stream()
                .map(event -> toResponse(
                        event,
                        listings.get(event.getListingId()),
                        products.get(event.getProductId()),
                        sellers.get(event.getSellerId()),
                        inventories.get(event.getProductId())
                ))
                .flatMap(Optional::stream)
                .toList();
        return new PageImpl<>(content, events.getPageable(), events.getTotalElements());
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
                .sellerVerificationStatus(sellerOpt
                        .map(profile -> profile.getVerificationStatus().name())
                        .orElse("UNVERIFIED"))
                .productId(event.getProductId())
                .title(title)
                .description(description)
                .imageUrl(listing.getImageUrl())
                .category(productOpt.map(Product::getCategory).orElse(""))
                .digitalTicket("DIGITAL_TICKET".equalsIgnoreCase(listing.getItemCondition()))
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

    private Optional<MarketplaceEventResponse> toResponse(SaleEvent event,
                                                          MarketplaceListing listing,
                                                          Product product,
                                                          SellerProfile seller,
                                                          Inventory inventory) {
        if (listing == null || listing.getStatus() != ListingStatus.ACTIVE) {
            return Optional.empty();
        }
        String title = notBlank(listing.getTitle())
                ? listing.getTitle()
                : product != null ? product.getName() : event.getProductId();
        String description = notBlank(listing.getDescription())
                ? listing.getDescription()
                : product != null ? product.getDescription() : "";
        return Optional.of(MarketplaceEventResponse.builder()
                .saleEventId(event.getSaleEventId())
                .listingId(event.getListingId())
                .sellerId(event.getSellerId())
                .sellerName(seller != null ? seller.getDisplayName() : "EverySale Partner")
                .sellerVerificationStatus(seller != null && seller.getVerificationStatus() != null
                        ? seller.getVerificationStatus().name()
                        : "UNVERIFIED")
                .productId(event.getProductId())
                .title(title)
                .description(description)
                .imageUrl(listing.getImageUrl())
                .category(product != null ? product.getCategory() : "")
                .digitalTicket("DIGITAL_TICKET".equalsIgnoreCase(listing.getItemCondition()))
                .saleType(event.getSaleType())
                .status(event.getStatus())
                .price(event.getPrice())
                .currency("KRW")
                .totalQuantity(inventory != null ? inventory.getTotalQuantity() : event.getStockQuantity())
                .availableQuantity(inventory != null ? inventory.getAvailableQuantity() : event.getStockQuantity())
                .minBidIncrement(event.getMinBidIncrement())
                .reservePrice(event.getReservePrice())
                .startsAt(event.getStartsAt())
                .endsAt(event.getEndsAt())
                .build());
    }

    private Sort eventSort(String sort) {
        String normalized = notBlank(sort) ? sort.trim().toLowerCase(Locale.ROOT) : "startsat";
        return switch (normalized) {
            case "endingsoon" -> Sort.by(Sort.Direction.ASC, "endsAt");
            case "priceasc" -> Sort.by(Sort.Direction.ASC, "price");
            case "pricedesc" -> Sort.by(Sort.Direction.DESC, "price");
            default -> Sort.by(Sort.Direction.ASC, "startsAt").and(Sort.by("saleEventId"));
        };
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

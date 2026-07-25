package com.example.payment.application.service;

import com.example.payment.domain.model.inventory.Inventory;
import com.example.payment.domain.model.inventory.Product;
import com.example.payment.domain.model.marketplace.ListingStatus;
import com.example.payment.domain.model.marketplace.MarketplaceListing;
import com.example.payment.domain.model.marketplace.SaleEvent;
import com.example.payment.domain.model.marketplace.SaleEventStatus;
import com.example.payment.domain.model.marketplace.SaleType;
import com.example.payment.domain.model.marketplace.SellerProfile;
import com.example.payment.domain.model.marketplace.SellerStatus;
import com.example.payment.domain.model.marketplace.SellerVerificationStatus;
import com.example.payment.domain.repository.InventoryRepository;
import com.example.payment.domain.repository.MarketplaceListingRepository;
import com.example.payment.domain.repository.ProductRepository;
import com.example.payment.domain.repository.SaleEventRepository;
import com.example.payment.domain.repository.SellerProfileRepository;
import com.example.payment.infrastructure.util.ResourceReservationService;
import com.example.payment.presentation.dto.request.CreateSaleEventRequest;
import com.example.payment.presentation.dto.request.CreateSellerListingRequest;
import com.example.payment.presentation.dto.request.CreateSellerRequest;
import com.example.payment.presentation.dto.request.ReviewSellerVerificationRequest;
import com.example.payment.presentation.dto.request.ReviewListingRequest;
import com.example.payment.presentation.dto.request.SubmitSellerVerificationRequest;
import com.example.payment.presentation.dto.request.UpdateC2CListingRequest;
import com.example.payment.presentation.dto.response.SellerListingResponse;
import com.example.payment.presentation.dto.response.SellerResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SellerMarketplaceService {

    private final SellerProfileRepository sellerProfileRepository;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final MarketplaceListingRepository marketplaceListingRepository;
    private final SaleEventRepository saleEventRepository;
    private final ResourceReservationService resourceReservationService;

    @Transactional
    public SellerResponse createSeller(CreateSellerRequest request) {
        SellerProfile seller = sellerProfileRepository.save(SellerProfile.builder()
                .sellerId("SELLER-" + shortId())
                .displayName(request.getDisplayName().trim())
                .status(SellerStatus.PENDING)
                .verificationStatus(SellerVerificationStatus.UNVERIFIED)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
        return toSellerResponse(seller);
    }

    @Transactional
    public SellerResponse createSellerForOwner(String ownerUserId, String ownerCustomerId, CreateSellerRequest request) {
        Optional<SellerProfile> existing = findSellerByOwner(ownerUserId, ownerCustomerId);
        if (existing.isPresent()) {
            return toSellerResponse(existing.get());
        }
        SellerProfile seller = sellerProfileRepository.save(SellerProfile.builder()
                .sellerId("SELLER-" + shortId())
                .displayName(request.getDisplayName().trim())
                .ownerUserId(ownerUserId)
                .ownerCustomerId(ownerCustomerId)
                .status(SellerStatus.PENDING)
                .verificationStatus(SellerVerificationStatus.UNVERIFIED)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
        return toSellerResponse(seller);
    }

    @Transactional(readOnly = true)
    public Optional<SellerResponse> getSellerByOwner(String ownerUserId, String ownerCustomerId) {
        return findSellerByOwner(ownerUserId, ownerCustomerId).map(this::toSellerResponse);
    }

    @Transactional
    public SellerResponse approveSeller(String sellerId) {
        SellerProfile seller = requireSeller(sellerId);
        seller.setStatus(SellerStatus.ACTIVE);
        seller.setVerificationStatus(SellerVerificationStatus.VERIFIED);
        seller.setVerificationReviewedAt(LocalDateTime.now());
        seller.setVerificationReviewedBy("system-admin");
        seller.setVerificationNote(defaultText(seller.getVerificationNote(), "Approved"));
        return toSellerResponse(sellerProfileRepository.save(seller));
    }

    @Transactional
    public SellerResponse submitSellerVerification(String ownerUserId,
                                                   String ownerCustomerId,
                                                   SubmitSellerVerificationRequest request) {
        SellerProfile seller = requireSellerForOwner(ownerUserId, ownerCustomerId);
        if (seller.getStatus() == SellerStatus.SUSPENDED) {
            throw new IllegalArgumentException("Suspended seller cannot submit verification: " + seller.getSellerId());
        }
        if (seller.getVerificationStatus() == SellerVerificationStatus.VERIFIED) {
            return toSellerResponse(seller);
        }
        seller.setVerificationStatus(SellerVerificationStatus.PENDING_REVIEW);
        seller.setVerificationEvidenceRef(request.getEvidenceRef().trim());
        seller.setVerificationNote(trimToNull(request.getNote()));
        seller.setVerificationSubmittedAt(LocalDateTime.now());
        seller.setVerificationReviewedBy(null);
        seller.setVerificationReviewedAt(null);
        return toSellerResponse(sellerProfileRepository.save(seller));
    }

    @Transactional
    public SellerResponse reviewSellerVerification(String sellerId,
                                                   String operatorId,
                                                   ReviewSellerVerificationRequest request) {
        SellerProfile seller = requireSeller(sellerId);
        if (seller.getVerificationStatus() != SellerVerificationStatus.PENDING_REVIEW) {
            throw new IllegalArgumentException("Seller verification is not waiting for review: " + sellerId);
        }

        seller.setVerificationReviewedBy(defaultText(operatorId, "admin"));
        seller.setVerificationReviewedAt(LocalDateTime.now());
        seller.setVerificationNote(defaultText(request.getNote(), Boolean.TRUE.equals(request.getApproved())
                ? "Seller verification approved."
                : "Seller verification rejected."));
        if (Boolean.TRUE.equals(request.getApproved())) {
            seller.setStatus(SellerStatus.ACTIVE);
            seller.setVerificationStatus(SellerVerificationStatus.VERIFIED);
        } else {
            seller.setStatus(SellerStatus.PENDING);
            seller.setVerificationStatus(SellerVerificationStatus.REJECTED);
        }
        return toSellerResponse(sellerProfileRepository.save(seller));
    }

    @Transactional
    public SellerListingResponse createDraftListingForOwner(String ownerUserId,
                                                            String ownerCustomerId,
                                                            CreateSellerListingRequest request) {
        SellerProfile seller = requireSellerForOwner(ownerUserId, ownerCustomerId);
        requireSellerCanDraft(seller);

        String productId = "PROD-" + shortId();
        String listingId = "LIST-" + shortId();

        Product product = productRepository.save(Product.builder()
                .id(productId)
                .name(request.getName().trim())
                .description(request.getDescription())
                .price(request.getPrice())
                .category(normalizeCategory(request.getCategory()))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());

        Inventory inventory = inventoryRepository.save(Inventory.builder()
                .productId(productId)
                .totalQuantity(request.getQuantity())
                .availableQuantity(request.getQuantity())
                .reservedQuantity(0)
                .version(0L)
                .lastUpdatedAt(LocalDateTime.now())
                .build());
        resourceReservationService.initializeResource("inventory:" + productId, request.getQuantity(), request.getQuantity());

        MarketplaceListing listing = marketplaceListingRepository.save(MarketplaceListing.builder()
                .listingId(listingId)
                .sellerId(seller.getSellerId())
                .productId(productId)
                .title(request.getName().trim())
                .description(request.getDescription())
                .imageUrl(request.getImageUrl())
                .itemCondition(defaultText(request.getItemCondition(), "GOOD"))
                .brand(trimToNull(request.getBrand()))
                .tags(trimToNull(request.getTags()))
                .authenticityNote(trimToNull(request.getAuthenticityNote()))
                .defectDescription(trimToNull(request.getDefectDescription()))
                .status(ListingStatus.DRAFT)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());

        return toListingResponse(listing, product, inventory, null);
    }

    @Transactional(readOnly = true)
    public List<SellerListingResponse> getListingsByOwner(String ownerUserId, String ownerCustomerId) {
        SellerProfile seller = requireSellerForOwner(ownerUserId, ownerCustomerId);
        return getListings(seller.getSellerId());
    }

    @Transactional
    public SellerListingResponse updateDraftListingForOwner(String ownerUserId,
                                                            String ownerCustomerId,
                                                            String listingId,
                                                            UpdateC2CListingRequest request) {
        SellerProfile seller = requireSellerForOwner(ownerUserId, ownerCustomerId);
        MarketplaceListing listing = requireOwnedListing(seller.getSellerId(), listingId);
        requireEditableListing(listing);

        Product product = productRepository.findById(listing.getProductId())
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + listing.getProductId()));
        Inventory inventory = inventoryRepository.findById(listing.getProductId())
                .orElseThrow(() -> new IllegalArgumentException("Inventory not found: " + listing.getProductId()));

        if (hasText(request.getName())) {
            product.setName(request.getName().trim());
            listing.setTitle(request.getName().trim());
        }
        if (request.getDescription() != null) {
            product.setDescription(request.getDescription());
            listing.setDescription(request.getDescription());
        }
        if (request.getPrice() != null) {
            product.setPrice(request.getPrice());
        }
        if (hasText(request.getCategory())) {
            product.setCategory(normalizeCategory(request.getCategory()));
        }
        if (request.getQuantity() != null) {
            inventory.setTotalQuantity(request.getQuantity());
            inventory.setAvailableQuantity(request.getQuantity());
            inventory.setReservedQuantity(0);
            resourceReservationService.initializeResource("inventory:" + product.getId(), request.getQuantity(), request.getQuantity());
        }
        if (request.getImageUrl() != null) {
            listing.setImageUrl(request.getImageUrl());
        }
        if (request.getItemCondition() != null) {
            listing.setItemCondition(defaultText(request.getItemCondition(), "GOOD"));
        }
        if (request.getBrand() != null) {
            listing.setBrand(trimToNull(request.getBrand()));
        }
        if (request.getTags() != null) {
            listing.setTags(trimToNull(request.getTags()));
        }
        if (request.getAuthenticityNote() != null) {
            listing.setAuthenticityNote(trimToNull(request.getAuthenticityNote()));
        }
        if (request.getDefectDescription() != null) {
            listing.setDefectDescription(trimToNull(request.getDefectDescription()));
        }
        listing.setReviewNote(null);
        listing.setReviewedBy(null);
        listing.setReviewedAt(null);

        Product savedProduct = productRepository.save(product);
        Inventory savedInventory = inventoryRepository.save(inventory);
        MarketplaceListing savedListing = marketplaceListingRepository.save(listing);
        return toListingResponse(savedListing, savedProduct, savedInventory, latestSaleEvent(savedListing));
    }

    @Transactional
    public SellerListingResponse submitListingForReview(String ownerUserId, String ownerCustomerId, String listingId) {
        SellerProfile seller = requireSellerForOwner(ownerUserId, ownerCustomerId);
        requireSellerCanDraft(seller);
        MarketplaceListing listing = requireOwnedListing(seller.getSellerId(), listingId);
        requireEditableListing(listing);

        Product product = productRepository.findById(listing.getProductId())
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + listing.getProductId()));
        Inventory inventory = inventoryRepository.findById(listing.getProductId())
                .orElseThrow(() -> new IllegalArgumentException("Inventory not found: " + listing.getProductId()));
        validateListingReadyForReview(listing, product, inventory);

        listing.setStatus(ListingStatus.PENDING_REVIEW);
        listing.setReviewNote(null);
        listing.setReviewedBy(null);
        listing.setReviewedAt(null);
        MarketplaceListing savedListing = marketplaceListingRepository.save(listing);
        return toListingResponse(savedListing, product, inventory, latestSaleEvent(savedListing));
    }

    @Transactional
    public SellerListingResponse createSaleEventForOwner(String ownerUserId,
                                                         String ownerCustomerId,
                                                         String listingId,
                                                         CreateSaleEventRequest request) {
        SellerProfile seller = requireSellerForOwner(ownerUserId, ownerCustomerId);
        return createSaleEvent(seller.getSellerId(), listingId, request);
    }

    @Transactional
    public SellerListingResponse publishSaleEventForOwner(String ownerUserId,
                                                          String ownerCustomerId,
                                                          String eventId) {
        SellerProfile seller = requireSellerForOwner(ownerUserId, ownerCustomerId);
        return publishSaleEvent(seller.getSellerId(), eventId);
    }

    @Transactional(readOnly = true)
    public SellerResponse getSeller(String sellerId) {
        return sellerProfileRepository.findById(sellerId)
                .map(this::toSellerResponse)
                .orElseThrow(() -> new IllegalArgumentException("Seller not found: " + sellerId));
    }

    @Transactional
    public SellerListingResponse createListing(String sellerId, CreateSellerListingRequest request) {
        SellerProfile seller = requireActiveSeller(sellerId);
        String productId = "PROD-" + shortId();
        String listingId = "LIST-" + shortId();

        Product product = productRepository.save(Product.builder()
                .id(productId)
                .name(request.getName().trim())
                .description(request.getDescription())
                .price(request.getPrice())
                .category(normalizeCategory(request.getCategory()))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());

        Inventory inventory = inventoryRepository.save(Inventory.builder()
                .productId(productId)
                .totalQuantity(request.getQuantity())
                .availableQuantity(request.getQuantity())
                .reservedQuantity(0)
                .version(0L)
                .lastUpdatedAt(LocalDateTime.now())
                .build());

        resourceReservationService.initializeResource("inventory:" + productId, request.getQuantity(), request.getQuantity());

        MarketplaceListing listing = marketplaceListingRepository.save(MarketplaceListing.builder()
                .listingId(listingId)
                .sellerId(seller.getSellerId())
                .productId(productId)
                .title(product.getName())
                .description(product.getDescription())
                .imageUrl(request.getImageUrl())
                .itemCondition(defaultText(request.getItemCondition(), "NEW"))
                .brand(trimToNull(request.getBrand()))
                .tags(trimToNull(request.getTags()))
                .authenticityNote(trimToNull(request.getAuthenticityNote()))
                .defectDescription(trimToNull(request.getDefectDescription()))
                .status(ListingStatus.PENDING_REVIEW)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());

        SaleType saleType = request.getSaleType() != null
                ? request.getSaleType()
                : mapCategoryToSaleType(product.getCategory());
        SaleEvent event = saleEventRepository.save(SaleEvent.builder()
                .saleEventId("EVT-" + shortId())
                .listingId(listing.getListingId())
                .sellerId(seller.getSellerId())
                .productId(productId)
                .saleType(saleType)
                .status(initialSaleEventStatus(listing, Boolean.TRUE.equals(request.getPublishImmediately())))
                .startsAt(defaultStart(request.getStartsAt(), Boolean.TRUE.equals(request.getPublishImmediately())))
                .endsAt(defaultEnd(request.getEndsAt(), saleType))
                .price(product.getPrice())
                .stockQuantity(inventory.getTotalQuantity())
                .minBidIncrement(request.getMinBidIncrement())
                .reservePrice(request.getReservePrice())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());

        return toListingResponse(listing, product, inventory, event);
    }

    @Transactional(readOnly = true)
    public List<SellerListingResponse> getListings(String sellerId) {
        requireSeller(sellerId);
        return marketplaceListingRepository.findBySellerIdOrderByCreatedAtDesc(sellerId)
                .stream()
                .map(this::toListingResponse)
                .toList();
    }

    @Transactional
    public SellerListingResponse createSaleEvent(String sellerId, String listingId, CreateSaleEventRequest request) {
        SellerProfile seller = requireActiveSeller(sellerId);
        MarketplaceListing listing = marketplaceListingRepository.findById(listingId)
                .orElseThrow(() -> new IllegalArgumentException("Listing not found: " + listingId));
        if (!seller.getSellerId().equals(listing.getSellerId())) {
            throw new IllegalArgumentException("Listing does not belong to seller: " + listingId);
        }
        if (listing.getStatus() != ListingStatus.ACTIVE) {
            throw new IllegalArgumentException("Listing must be approved before creating sale events: " + listingId);
        }

        Inventory inventory = inventoryRepository.findById(listing.getProductId())
                .orElseThrow(() -> new IllegalArgumentException("Inventory not found for product: " + listing.getProductId()));
        int availableQuantity = Optional.ofNullable(inventory.getAvailableQuantity()).orElse(0);
        if (request.getStockQuantity() > availableQuantity) {
            throw new IllegalArgumentException("Sale event stock cannot exceed available inventory: " + listingId);
        }

        SaleEvent event = saleEventRepository.save(SaleEvent.builder()
                .saleEventId("EVT-" + shortId())
                .listingId(listingId)
                .sellerId(sellerId)
                .productId(listing.getProductId())
                .saleType(request.getSaleType())
                .status(Boolean.TRUE.equals(request.getPublishImmediately())
                        ? SaleEventStatus.LIVE
                        : SaleEventStatus.SCHEDULED)
                .startsAt(defaultStart(request.getStartsAt(), Boolean.TRUE.equals(request.getPublishImmediately())))
                .endsAt(defaultEnd(request.getEndsAt(), request.getSaleType()))
                .price(request.getPrice())
                .stockQuantity(request.getStockQuantity())
                .minBidIncrement(request.getMinBidIncrement())
                .reservePrice(request.getReservePrice())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());

        Product product = productRepository.findById(listing.getProductId())
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + listing.getProductId()));
        return toListingResponse(listing, product, inventory, event);
    }

    @Transactional
    public SellerListingResponse publishSaleEvent(String sellerId, String eventId) {
        requireActiveSeller(sellerId);
        SaleEvent event = saleEventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("Sale event not found: " + eventId));
        if (!sellerId.equals(event.getSellerId())) {
            throw new IllegalArgumentException("Sale event does not belong to seller: " + eventId);
        }
        MarketplaceListing listing = marketplaceListingRepository.findById(event.getListingId())
                .orElseThrow(() -> new IllegalArgumentException("Listing not found: " + event.getListingId()));
        if (listing.getStatus() != ListingStatus.ACTIVE) {
            throw new IllegalArgumentException("Listing must be approved before publishing: " + listing.getListingId());
        }

        event.setStatus(SaleEventStatus.LIVE);
        if (event.getStartsAt() == null || event.getStartsAt().isAfter(LocalDateTime.now())) {
            event.setStartsAt(LocalDateTime.now());
        }
        SaleEvent savedEvent = saleEventRepository.save(event);

        Product product = productRepository.findById(savedEvent.getProductId())
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + savedEvent.getProductId()));
        Inventory inventory = inventoryRepository.findById(savedEvent.getProductId())
                .orElseThrow(() -> new IllegalArgumentException("Inventory not found: " + savedEvent.getProductId()));

        return toListingResponse(listing, product, inventory, savedEvent);
    }

    @Transactional(readOnly = true)
    public List<SellerListingResponse> getListingsForReview(ListingStatus status) {
        ListingStatus reviewStatus = status != null ? status : ListingStatus.PENDING_REVIEW;
        return marketplaceListingRepository.findByStatusOrderByCreatedAtAsc(reviewStatus)
                .stream()
                .map(this::toListingResponse)
                .toList();
    }

    @Transactional
    public SellerListingResponse approveListing(String listingId, ReviewListingRequest request) {
        MarketplaceListing listing = marketplaceListingRepository.findById(listingId)
                .orElseThrow(() -> new IllegalArgumentException("Listing not found: " + listingId));
        if (listing.getStatus() == ListingStatus.REJECTED) {
            throw new IllegalArgumentException("Rejected listing cannot be approved without resubmission: " + listingId);
        }
        listing.setStatus(ListingStatus.ACTIVE);
        listing.setReviewedBy(request.getOperatorId().trim());
        listing.setReviewedAt(LocalDateTime.now());
        listing.setReviewNote(defaultText(request.getNote(), "Approved"));
        MarketplaceListing savedListing = marketplaceListingRepository.save(listing);

        SaleEvent event = latestSaleEvent(savedListing);
        if (event != null && event.getStatus() == SaleEventStatus.SCHEDULED && isReadyToGoLive(event)) {
            event.setStatus(SaleEventStatus.LIVE);
            event = saleEventRepository.save(event);
        }

        Product product = productRepository.findById(savedListing.getProductId())
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + savedListing.getProductId()));
        Inventory inventory = inventoryRepository.findById(savedListing.getProductId())
                .orElseThrow(() -> new IllegalArgumentException("Inventory not found: " + savedListing.getProductId()));
        return toListingResponse(savedListing, product, inventory, event);
    }

    @Transactional
    public SellerListingResponse rejectListing(String listingId, ReviewListingRequest request) {
        MarketplaceListing listing = marketplaceListingRepository.findById(listingId)
                .orElseThrow(() -> new IllegalArgumentException("Listing not found: " + listingId));
        if (listing.getStatus() == ListingStatus.ACTIVE) {
            throw new IllegalArgumentException("Active listing must be paused or ended instead of rejected: " + listingId);
        }

        listing.setStatus(ListingStatus.REJECTED);
        listing.setReviewedBy(request.getOperatorId().trim());
        listing.setReviewedAt(LocalDateTime.now());
        listing.setReviewNote(defaultText(request.getNote(), "Rejected"));
        MarketplaceListing savedListing = marketplaceListingRepository.save(listing);

        SaleEvent event = null;
        for (SaleEvent saleEvent : saleEventRepository.findByListingIdOrderByStartsAtDesc(listingId)) {
            if (saleEvent.getStatus() == SaleEventStatus.SCHEDULED || saleEvent.getStatus() == SaleEventStatus.LIVE) {
                saleEvent.setStatus(SaleEventStatus.CANCELLED);
                event = saleEventRepository.save(saleEvent);
            }
        }
        if (event == null) {
            event = latestSaleEvent(savedListing);
        }

        Product product = productRepository.findById(savedListing.getProductId())
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + savedListing.getProductId()));
        Inventory inventory = inventoryRepository.findById(savedListing.getProductId())
                .orElseThrow(() -> new IllegalArgumentException("Inventory not found: " + savedListing.getProductId()));
        return toListingResponse(savedListing, product, inventory, event);
    }

    private SellerProfile requireSeller(String sellerId) {
        return sellerProfileRepository.findById(sellerId)
                .orElseThrow(() -> new IllegalArgumentException("Seller not found: " + sellerId));
    }

    private Optional<SellerProfile> findSellerByOwner(String ownerUserId, String ownerCustomerId) {
        if (ownerUserId != null && !ownerUserId.isBlank()) {
            Optional<SellerProfile> byUser = sellerProfileRepository.findByOwnerUserId(ownerUserId);
            if (byUser.isPresent()) {
                return byUser;
            }
        }
        if (ownerCustomerId != null && !ownerCustomerId.isBlank()) {
            return sellerProfileRepository.findByOwnerCustomerId(ownerCustomerId);
        }
        return Optional.empty();
    }

    private SellerProfile requireActiveSeller(String sellerId) {
        SellerProfile seller = requireSeller(sellerId);
        if (seller.getStatus() != SellerStatus.ACTIVE) {
            throw new IllegalArgumentException("Seller is not active: " + sellerId);
        }
        return seller;
    }

    private SellerProfile requireSellerForOwner(String ownerUserId, String ownerCustomerId) {
        return findSellerByOwner(ownerUserId, ownerCustomerId)
                .orElseThrow(() -> new IllegalArgumentException("Seller profile has not been created for the current user."));
    }

    private void requireSellerCanDraft(SellerProfile seller) {
        if (seller.getStatus() == SellerStatus.SUSPENDED) {
            throw new IllegalArgumentException("Seller is suspended: " + seller.getSellerId());
        }
    }

    private MarketplaceListing requireOwnedListing(String sellerId, String listingId) {
        MarketplaceListing listing = marketplaceListingRepository.findById(listingId)
                .orElseThrow(() -> new IllegalArgumentException("Listing not found: " + listingId));
        if (!sellerId.equals(listing.getSellerId())) {
            throw new IllegalArgumentException("Listing does not belong to seller: " + listingId);
        }
        return listing;
    }

    private void requireEditableListing(MarketplaceListing listing) {
        if (listing.getStatus() != ListingStatus.DRAFT && listing.getStatus() != ListingStatus.REJECTED) {
            throw new IllegalArgumentException("Only draft or rejected listings can be edited or submitted: " + listing.getListingId());
        }
        if (!saleEventRepository.findByListingIdOrderByStartsAtDesc(listing.getListingId()).isEmpty()) {
            throw new IllegalArgumentException("Listing already has a sale event and cannot be edited as a C2C draft: " + listing.getListingId());
        }
    }

    private void validateListingReadyForReview(MarketplaceListing listing, Product product, Inventory inventory) {
        if (!hasText(listing.getTitle()) || !hasText(product.getCategory())) {
            throw new IllegalArgumentException("Listing title and category are required before review submission.");
        }
        if (product.getPrice() == null || product.getPrice().signum() <= 0) {
            throw new IllegalArgumentException("Listing price must be greater than 0 before review submission.");
        }
        if (inventory.getTotalQuantity() == null || inventory.getTotalQuantity() <= 0) {
            throw new IllegalArgumentException("Listing quantity must be greater than 0 before review submission.");
        }
    }

    private SellerListingResponse toListingResponse(MarketplaceListing listing) {
        Product product = productRepository.findById(listing.getProductId())
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + listing.getProductId()));
        Inventory inventory = inventoryRepository.findById(listing.getProductId())
                .orElseThrow(() -> new IllegalArgumentException("Inventory not found: " + listing.getProductId()));
        SaleEvent event = saleEventRepository.findByListingIdOrderByStartsAtDesc(listing.getListingId())
                .stream()
                .findFirst()
                .orElse(null);
        return toListingResponse(listing, product, inventory, event);
    }

    private SellerListingResponse toListingResponse(MarketplaceListing listing, Product product,
                                                    Inventory inventory, SaleEvent event) {
        return SellerListingResponse.builder()
                .listingId(listing.getListingId())
                .sellerId(listing.getSellerId())
                .productId(product.getId())
                .name(listing.getTitle())
                .description(listing.getDescription())
                .imageUrl(listing.getImageUrl())
                .category(product.getCategory())
                .itemCondition(listing.getItemCondition())
                .brand(listing.getBrand())
                .tags(listing.getTags())
                .authenticityNote(listing.getAuthenticityNote())
                .defectDescription(listing.getDefectDescription())
                .status(listing.getStatus())
                .price(event != null ? event.getPrice() : product.getPrice())
                .totalQuantity(inventory.getTotalQuantity())
                .availableQuantity(inventory.getAvailableQuantity())
                .saleEventId(event != null ? event.getSaleEventId() : null)
                .saleType(event != null ? event.getSaleType() : null)
                .saleEventStatus(event != null ? event.getStatus() : null)
                .startsAt(event != null ? event.getStartsAt() : null)
                .endsAt(event != null ? event.getEndsAt() : null)
                .reviewedBy(listing.getReviewedBy())
                .reviewedAt(listing.getReviewedAt())
                .reviewNote(listing.getReviewNote())
                .build();
    }

    private SellerResponse toSellerResponse(SellerProfile seller) {
        return SellerResponse.builder()
                .sellerId(seller.getSellerId())
                .displayName(seller.getDisplayName())
                .ownerUserId(seller.getOwnerUserId())
                .ownerCustomerId(seller.getOwnerCustomerId())
                .status(seller.getStatus())
                .verificationStatus(seller.getVerificationStatus())
                .verificationEvidenceRef(seller.getVerificationEvidenceRef())
                .verificationNote(seller.getVerificationNote())
                .verificationSubmittedAt(seller.getVerificationSubmittedAt())
                .verificationReviewedBy(seller.getVerificationReviewedBy())
                .verificationReviewedAt(seller.getVerificationReviewedAt())
                .createdAt(seller.getCreatedAt())
                .build();
    }

    private SaleType mapCategoryToSaleType(String category) {
        return switch (category) {
            case "DRAW" -> SaleType.RAFFLE;
            case "AUCTION" -> SaleType.AUCTION;
            case "TICKETING" -> SaleType.DROP;
            default -> SaleType.FIXED_PRICE;
        };
    }

    private SaleEventStatus initialSaleEventStatus(MarketplaceListing listing, boolean publishImmediately) {
        return listing.getStatus() == ListingStatus.ACTIVE && publishImmediately
                ? SaleEventStatus.LIVE
                : SaleEventStatus.SCHEDULED;
    }

    private SaleEvent latestSaleEvent(MarketplaceListing listing) {
        return saleEventRepository.findByListingIdOrderByStartsAtDesc(listing.getListingId())
                .stream()
                .findFirst()
                .orElse(null);
    }

    private boolean isReadyToGoLive(SaleEvent event) {
        return event.getStartsAt() == null || !event.getStartsAt().isAfter(LocalDateTime.now());
    }

    private String normalizeCategory(String category) {
        return category.trim().toUpperCase(Locale.ROOT);
    }

    private LocalDateTime defaultStart(LocalDateTime requested, boolean publishImmediately) {
        if (requested != null) {
            return requested;
        }
        return publishImmediately ? LocalDateTime.now() : LocalDateTime.now().plusHours(1);
    }

    private LocalDateTime defaultEnd(LocalDateTime requested, SaleType saleType) {
        if (requested != null) {
            return requested;
        }
        return switch (saleType) {
            case AUCTION -> LocalDateTime.now().plusHours(6);
            case RAFFLE -> LocalDateTime.now().plusDays(7);
            case DROP -> LocalDateTime.now().plusDays(1);
            case FIXED_PRICE -> null;
        };
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }
}

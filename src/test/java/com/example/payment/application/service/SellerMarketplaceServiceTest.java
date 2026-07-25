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
import com.example.payment.presentation.dto.request.ReviewListingRequest;
import com.example.payment.presentation.dto.request.ReviewSellerVerificationRequest;
import com.example.payment.presentation.dto.request.SubmitSellerVerificationRequest;
import com.example.payment.presentation.dto.request.UpdateC2CListingRequest;
import com.example.payment.presentation.dto.response.SellerListingResponse;
import com.example.payment.presentation.dto.response.SellerResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SellerMarketplaceServiceTest {

    private final SellerProfileRepository sellerRepository = mock(SellerProfileRepository.class);
    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final InventoryRepository inventoryRepository = mock(InventoryRepository.class);
    private final MarketplaceListingRepository listingRepository = mock(MarketplaceListingRepository.class);
    private final SaleEventRepository saleEventRepository = mock(SaleEventRepository.class);
    private final ResourceReservationService resourceReservationService = mock(ResourceReservationService.class);

    private final SellerMarketplaceService service = new SellerMarketplaceService(
            sellerRepository,
            productRepository,
            inventoryRepository,
            listingRepository,
            saleEventRepository,
            resourceReservationService
    );

    @Test
    void approvesSellerForC2CSaleEventCreation() {
        SellerProfile seller = SellerProfile.builder()
                .sellerId("SELLER-C2C")
                .displayName("C2C Seller")
                .ownerUserId("USER-1")
                .ownerCustomerId("CUST-1")
                .status(SellerStatus.PENDING)
                .verificationStatus(SellerVerificationStatus.UNVERIFIED)
                .build();
        when(sellerRepository.findById("SELLER-C2C")).thenReturn(Optional.of(seller));
        when(sellerRepository.save(any(SellerProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SellerResponse response = service.approveSeller("SELLER-C2C");

        assertEquals(SellerStatus.ACTIVE, response.getStatus());
        assertEquals(SellerVerificationStatus.VERIFIED, response.getVerificationStatus());
    }

    @Test
    void submitsSellerVerificationForOwnerReview() {
        SellerProfile seller = SellerProfile.builder()
                .sellerId("SELLER-C2C")
                .displayName("C2C Seller")
                .ownerUserId("USER-1")
                .ownerCustomerId("CUST-1")
                .status(SellerStatus.PENDING)
                .verificationStatus(SellerVerificationStatus.UNVERIFIED)
                .build();
        when(sellerRepository.findByOwnerUserId("USER-1")).thenReturn(Optional.of(seller));
        when(sellerRepository.save(any(SellerProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SubmitSellerVerificationRequest request = new SubmitSellerVerificationRequest();
        request.setEvidenceRef("kyc://seller-c2c/evidence-1");
        request.setNote("Identity and payout account evidence submitted.");

        SellerResponse response = service.submitSellerVerification("USER-1", "CUST-1", request);

        assertEquals(SellerVerificationStatus.PENDING_REVIEW, response.getVerificationStatus());
        assertEquals("kyc://seller-c2c/evidence-1", response.getVerificationEvidenceRef());
        assertEquals("Identity and payout account evidence submitted.", response.getVerificationNote());
    }

    @Test
    void approvesPendingSellerVerificationAndActivatesSeller() {
        SellerProfile seller = SellerProfile.builder()
                .sellerId("SELLER-C2C")
                .displayName("C2C Seller")
                .status(SellerStatus.PENDING)
                .verificationStatus(SellerVerificationStatus.PENDING_REVIEW)
                .verificationEvidenceRef("kyc://seller-c2c/evidence-1")
                .build();
        when(sellerRepository.findById("SELLER-C2C")).thenReturn(Optional.of(seller));
        when(sellerRepository.save(any(SellerProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReviewSellerVerificationRequest request = new ReviewSellerVerificationRequest();
        request.setApproved(true);
        request.setNote("Documents verified.");

        SellerResponse response = service.reviewSellerVerification("SELLER-C2C", "ops-1", request);

        assertEquals(SellerStatus.ACTIVE, response.getStatus());
        assertEquals(SellerVerificationStatus.VERIFIED, response.getVerificationStatus());
        assertEquals("ops-1", response.getVerificationReviewedBy());
        assertEquals("Documents verified.", response.getVerificationNote());
    }

    @Test
    void rejectsPendingSellerVerificationWithoutActivatingSeller() {
        SellerProfile seller = SellerProfile.builder()
                .sellerId("SELLER-C2C")
                .displayName("C2C Seller")
                .status(SellerStatus.PENDING)
                .verificationStatus(SellerVerificationStatus.PENDING_REVIEW)
                .verificationEvidenceRef("kyc://seller-c2c/evidence-1")
                .build();
        when(sellerRepository.findById("SELLER-C2C")).thenReturn(Optional.of(seller));
        when(sellerRepository.save(any(SellerProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReviewSellerVerificationRequest request = new ReviewSellerVerificationRequest();
        request.setApproved(false);
        request.setNote("Payout account evidence is missing.");

        SellerResponse response = service.reviewSellerVerification("SELLER-C2C", "ops-1", request);

        assertEquals(SellerStatus.PENDING, response.getStatus());
        assertEquals(SellerVerificationStatus.REJECTED, response.getVerificationStatus());
        assertEquals("Payout account evidence is missing.", response.getVerificationNote());
    }

    @Test
    void rejectedSellerVerificationMustBeResubmittedBeforeReview() {
        SellerProfile seller = SellerProfile.builder()
                .sellerId("SELLER-C2C")
                .displayName("C2C Seller")
                .status(SellerStatus.PENDING)
                .verificationStatus(SellerVerificationStatus.REJECTED)
                .verificationEvidenceRef("kyc://seller-c2c/evidence-1")
                .build();
        when(sellerRepository.findById("SELLER-C2C")).thenReturn(Optional.of(seller));

        ReviewSellerVerificationRequest request = new ReviewSellerVerificationRequest();
        request.setApproved(true);
        request.setNote("Approve without resubmission.");

        assertThrows(IllegalArgumentException.class,
                () -> service.reviewSellerVerification("SELLER-C2C", "ops-1", request));
    }

    @Test
    void createsC2CDraftListingForOwnerWithoutPublicSaleEvent() {
        when(sellerRepository.findByOwnerUserId("USER-1")).thenReturn(Optional.of(SellerProfile.builder()
                .sellerId("SELLER-C2C")
                .displayName("C2C Seller")
                .ownerUserId("USER-1")
                .ownerCustomerId("CUST-1")
                .status(SellerStatus.PENDING)
                .verificationStatus(SellerVerificationStatus.UNVERIFIED)
                .build()));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(inventoryRepository.save(any(Inventory.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(listingRepository.save(any(MarketplaceListing.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CreateSellerListingRequest request = new CreateSellerListingRequest();
        request.setName("Vintage Camera");
        request.setDescription("Clean body, tested shutter.");
        request.setCategory("camera");
        request.setPrice(new BigDecimal("180000"));
        request.setQuantity(1);
        request.setBrand("Nikon");
        request.setTags("vintage,camera,film");
        request.setAuthenticityNote("Serial number photographed.");
        request.setDefectDescription("Minor paint wear.");

        SellerListingResponse response = service.createDraftListingForOwner("USER-1", "CUST-1", request);

        assertEquals("Vintage Camera", response.getName());
        assertEquals("CAMERA", response.getCategory());
        assertEquals(ListingStatus.DRAFT, response.getStatus());
        assertEquals("GOOD", response.getItemCondition());
        assertEquals("Nikon", response.getBrand());
        assertEquals("vintage,camera,film", response.getTags());
        assertEquals(1, response.getAvailableQuantity());
        assertEquals(null, response.getSaleEventId());

        verify(resourceReservationService).initializeResource(eq("inventory:" + response.getProductId()), eq(1), eq(1));
        verify(saleEventRepository, never()).save(any(SaleEvent.class));
    }

    @Test
    void updatesOnlyOwnedEditableC2CDraftListing() {
        SellerProfile seller = SellerProfile.builder()
                .sellerId("SELLER-C2C")
                .displayName("C2C Seller")
                .ownerUserId("USER-1")
                .ownerCustomerId("CUST-1")
                .status(SellerStatus.ACTIVE)
                .verificationStatus(SellerVerificationStatus.VERIFIED)
                .build();
        MarketplaceListing listing = MarketplaceListing.builder()
                .listingId("LIST-C2C")
                .sellerId("SELLER-C2C")
                .productId("PROD-C2C")
                .title("Old Title")
                .description("Old description")
                .status(ListingStatus.DRAFT)
                .build();
        Product product = Product.builder()
                .id("PROD-C2C")
                .name("Old Title")
                .category("GENERAL")
                .price(new BigDecimal("10000"))
                .build();
        Inventory inventory = Inventory.builder()
                .productId("PROD-C2C")
                .totalQuantity(1)
                .availableQuantity(1)
                .reservedQuantity(0)
                .build();
        when(sellerRepository.findByOwnerUserId("USER-1")).thenReturn(Optional.of(seller));
        when(listingRepository.findById("LIST-C2C")).thenReturn(Optional.of(listing));
        when(saleEventRepository.findByListingIdOrderByStartsAtDesc("LIST-C2C")).thenReturn(java.util.List.of());
        when(productRepository.findById("PROD-C2C")).thenReturn(Optional.of(product));
        when(inventoryRepository.findById("PROD-C2C")).thenReturn(Optional.of(inventory));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(inventoryRepository.save(any(Inventory.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(listingRepository.save(any(MarketplaceListing.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateC2CListingRequest request = new UpdateC2CListingRequest();
        request.setName("Restored Camera");
        request.setCategory("collectible");
        request.setPrice(new BigDecimal("210000"));
        request.setQuantity(2);
        request.setItemCondition("LIKE_NEW");
        request.setBrand("Nikon");

        SellerListingResponse response = service.updateDraftListingForOwner("USER-1", "CUST-1", "LIST-C2C", request);

        assertEquals("Restored Camera", response.getName());
        assertEquals("COLLECTIBLE", response.getCategory());
        assertEquals(new BigDecimal("210000"), response.getPrice());
        assertEquals(2, response.getTotalQuantity());
        assertEquals(2, response.getAvailableQuantity());
        assertEquals("LIKE_NEW", response.getItemCondition());
        assertEquals("Nikon", response.getBrand());
        assertEquals(ListingStatus.DRAFT, response.getStatus());

        verify(resourceReservationService).initializeResource("inventory:PROD-C2C", 2, 2);
    }

    @Test
    void submitsOwnedDraftListingForReview() {
        SellerProfile seller = SellerProfile.builder()
                .sellerId("SELLER-C2C")
                .displayName("C2C Seller")
                .ownerUserId("USER-1")
                .ownerCustomerId("CUST-1")
                .status(SellerStatus.ACTIVE)
                .verificationStatus(SellerVerificationStatus.VERIFIED)
                .build();
        MarketplaceListing listing = MarketplaceListing.builder()
                .listingId("LIST-C2C")
                .sellerId("SELLER-C2C")
                .productId("PROD-C2C")
                .title("Restored Camera")
                .status(ListingStatus.DRAFT)
                .reviewNote("Old reject note")
                .build();
        Product product = Product.builder()
                .id("PROD-C2C")
                .name("Restored Camera")
                .category("COLLECTIBLE")
                .price(new BigDecimal("210000"))
                .build();
        Inventory inventory = Inventory.builder()
                .productId("PROD-C2C")
                .totalQuantity(1)
                .availableQuantity(1)
                .reservedQuantity(0)
                .build();
        when(sellerRepository.findByOwnerUserId("USER-1")).thenReturn(Optional.of(seller));
        when(listingRepository.findById("LIST-C2C")).thenReturn(Optional.of(listing));
        when(saleEventRepository.findByListingIdOrderByStartsAtDesc("LIST-C2C")).thenReturn(java.util.List.of());
        when(productRepository.findById("PROD-C2C")).thenReturn(Optional.of(product));
        when(inventoryRepository.findById("PROD-C2C")).thenReturn(Optional.of(inventory));
        when(listingRepository.save(any(MarketplaceListing.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SellerListingResponse response = service.submitListingForReview("USER-1", "CUST-1", "LIST-C2C");

        assertEquals(ListingStatus.PENDING_REVIEW, response.getStatus());
        assertEquals(null, response.getReviewNote());
        assertEquals(null, response.getReviewedBy());
        assertEquals(null, response.getReviewedAt());
    }

    @Test
    void rejectsC2CUpdateForListingOwnedByAnotherSeller() {
        when(sellerRepository.findByOwnerUserId("USER-1")).thenReturn(Optional.of(SellerProfile.builder()
                .sellerId("SELLER-C2C")
                .displayName("C2C Seller")
                .ownerUserId("USER-1")
                .ownerCustomerId("CUST-1")
                .status(SellerStatus.ACTIVE)
                .verificationStatus(SellerVerificationStatus.VERIFIED)
                .build()));
        when(listingRepository.findById("LIST-OTHER")).thenReturn(Optional.of(MarketplaceListing.builder()
                .listingId("LIST-OTHER")
                .sellerId("SELLER-OTHER")
                .productId("PROD-OTHER")
                .title("Other Item")
                .status(ListingStatus.DRAFT)
                .build()));

        UpdateC2CListingRequest request = new UpdateC2CListingRequest();
        request.setName("Hijacked Title");

        assertThrows(IllegalArgumentException.class,
                () -> service.updateDraftListingForOwner("USER-1", "CUST-1", "LIST-OTHER", request));
    }

    @Test
    void rejectsSaleEventCreationUntilC2CListingIsApproved() {
        when(sellerRepository.findById("SELLER-C2C")).thenReturn(Optional.of(SellerProfile.builder()
                .sellerId("SELLER-C2C")
                .displayName("C2C Seller")
                .status(SellerStatus.ACTIVE)
                .verificationStatus(SellerVerificationStatus.VERIFIED)
                .build()));
        when(listingRepository.findById("LIST-C2C")).thenReturn(Optional.of(MarketplaceListing.builder()
                .listingId("LIST-C2C")
                .sellerId("SELLER-C2C")
                .productId("PROD-C2C")
                .title("Restored Camera")
                .status(ListingStatus.PENDING_REVIEW)
                .build()));

        CreateSaleEventRequest request = new CreateSaleEventRequest();
        request.setSaleType(SaleType.AUCTION);
        request.setPrice(new BigDecimal("210000"));
        request.setStockQuantity(1);

        assertThrows(IllegalArgumentException.class,
                () -> service.createSaleEvent("SELLER-C2C", "LIST-C2C", request));
    }

    @Test
    void createsSaleEventForCurrentOwnerAfterListingApproval() {
        SellerProfile seller = SellerProfile.builder()
                .sellerId("SELLER-C2C")
                .displayName("C2C Seller")
                .ownerUserId("USER-1")
                .ownerCustomerId("CUST-1")
                .status(SellerStatus.ACTIVE)
                .verificationStatus(SellerVerificationStatus.VERIFIED)
                .build();
        MarketplaceListing listing = MarketplaceListing.builder()
                .listingId("LIST-C2C")
                .sellerId("SELLER-C2C")
                .productId("PROD-C2C")
                .title("Restored Camera")
                .status(ListingStatus.ACTIVE)
                .build();
        Inventory inventory = Inventory.builder()
                .productId("PROD-C2C")
                .totalQuantity(1)
                .availableQuantity(1)
                .reservedQuantity(0)
                .build();
        Product product = Product.builder()
                .id("PROD-C2C")
                .name("Restored Camera")
                .category("COLLECTIBLE")
                .price(new BigDecimal("210000"))
                .build();
        when(sellerRepository.findByOwnerUserId("USER-1")).thenReturn(Optional.of(seller));
        when(sellerRepository.findById("SELLER-C2C")).thenReturn(Optional.of(seller));
        when(listingRepository.findById("LIST-C2C")).thenReturn(Optional.of(listing));
        when(inventoryRepository.findById("PROD-C2C")).thenReturn(Optional.of(inventory));
        when(productRepository.findById("PROD-C2C")).thenReturn(Optional.of(product));
        when(saleEventRepository.save(any(SaleEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CreateSaleEventRequest request = new CreateSaleEventRequest();
        request.setSaleType(SaleType.AUCTION);
        request.setPrice(new BigDecimal("210000"));
        request.setStockQuantity(1);
        request.setPublishImmediately(true);
        request.setMinBidIncrement(new BigDecimal("10000"));

        SellerListingResponse response = service.createSaleEventForOwner("USER-1", "CUST-1", "LIST-C2C", request);

        assertEquals(ListingStatus.ACTIVE, response.getStatus());
        assertEquals(SaleType.AUCTION, response.getSaleType());
        assertEquals(SaleEventStatus.LIVE, response.getSaleEventStatus());
        assertEquals(new BigDecimal("210000"), response.getPrice());

        ArgumentCaptor<SaleEvent> eventCaptor = ArgumentCaptor.forClass(SaleEvent.class);
        verify(saleEventRepository).save(eventCaptor.capture());
        assertEquals("SELLER-C2C", eventCaptor.getValue().getSellerId());
        assertEquals("LIST-C2C", eventCaptor.getValue().getListingId());
        assertEquals("PROD-C2C", eventCaptor.getValue().getProductId());
    }

    @Test
    void rejectsSaleEventStockThatExceedsAvailableInventory() {
        SellerProfile seller = SellerProfile.builder()
                .sellerId("SELLER-C2C")
                .displayName("C2C Seller")
                .ownerUserId("USER-1")
                .ownerCustomerId("CUST-1")
                .status(SellerStatus.ACTIVE)
                .verificationStatus(SellerVerificationStatus.VERIFIED)
                .build();
        when(sellerRepository.findByOwnerUserId("USER-1")).thenReturn(Optional.of(seller));
        when(sellerRepository.findById("SELLER-C2C")).thenReturn(Optional.of(seller));
        when(listingRepository.findById("LIST-C2C")).thenReturn(Optional.of(MarketplaceListing.builder()
                .listingId("LIST-C2C")
                .sellerId("SELLER-C2C")
                .productId("PROD-C2C")
                .title("Restored Camera")
                .status(ListingStatus.ACTIVE)
                .build()));
        when(inventoryRepository.findById("PROD-C2C")).thenReturn(Optional.of(Inventory.builder()
                .productId("PROD-C2C")
                .totalQuantity(1)
                .availableQuantity(1)
                .reservedQuantity(0)
                .build()));

        CreateSaleEventRequest request = new CreateSaleEventRequest();
        request.setSaleType(SaleType.DROP);
        request.setPrice(new BigDecimal("210000"));
        request.setStockQuantity(2);

        assertThrows(IllegalArgumentException.class,
                () -> service.createSaleEventForOwner("USER-1", "CUST-1", "LIST-C2C", request));
        verify(saleEventRepository, never()).save(any(SaleEvent.class));
    }

    @Test
    void createsRaffleListingAsPendingReviewBeforePublicPublish() {
        when(sellerRepository.findById("SELLER-1")).thenReturn(Optional.of(SellerProfile.builder()
                .sellerId("SELLER-1")
                .displayName("Verified Seller")
                .status(SellerStatus.ACTIVE)
                .verificationStatus(SellerVerificationStatus.VERIFIED)
                .build()));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(inventoryRepository.save(any(Inventory.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(listingRepository.save(any(MarketplaceListing.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(saleEventRepository.save(any(SaleEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CreateSellerListingRequest request = new CreateSellerListingRequest();
        request.setName("Limited Hoodie");
        request.setDescription("Drop exclusive item");
        request.setCategory("DRAW");
        request.setPrice(new BigDecimal("89000"));
        request.setQuantity(25);
        request.setPublishImmediately(true);

        SellerListingResponse response = service.createListing("SELLER-1", request);

        assertEquals("Limited Hoodie", response.getName());
        assertEquals(ListingStatus.PENDING_REVIEW, response.getStatus());
        assertEquals(SaleType.RAFFLE, response.getSaleType());
        assertEquals(SaleEventStatus.SCHEDULED, response.getSaleEventStatus());
        assertEquals(25, response.getAvailableQuantity());

        verify(resourceReservationService).initializeResource(eq("inventory:" + response.getProductId()), eq(25), eq(25));

        ArgumentCaptor<SaleEvent> eventCaptor = ArgumentCaptor.forClass(SaleEvent.class);
        verify(saleEventRepository).save(eventCaptor.capture());
        assertEquals(SaleType.RAFFLE, eventCaptor.getValue().getSaleType());
        assertEquals(SaleEventStatus.SCHEDULED, eventCaptor.getValue().getStatus());
    }

    @Test
    void approvalActivatesListingAndLiveEventWhenStartTimeHasArrived() {
        MarketplaceListing listing = MarketplaceListing.builder()
                .listingId("LIST-1")
                .sellerId("SELLER-1")
                .productId("PROD-1")
                .title("Limited Hoodie")
                .status(ListingStatus.PENDING_REVIEW)
                .build();
        SaleEvent event = SaleEvent.builder()
                .saleEventId("EVT-1")
                .listingId("LIST-1")
                .sellerId("SELLER-1")
                .productId("PROD-1")
                .saleType(SaleType.DROP)
                .status(SaleEventStatus.SCHEDULED)
                .startsAt(java.time.LocalDateTime.now().minusMinutes(1))
                .price(new BigDecimal("59000"))
                .stockQuantity(10)
                .build();
        when(listingRepository.findById("LIST-1")).thenReturn(Optional.of(listing));
        when(listingRepository.save(any(MarketplaceListing.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(saleEventRepository.findByListingIdOrderByStartsAtDesc("LIST-1")).thenReturn(java.util.List.of(event));
        when(saleEventRepository.save(any(SaleEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(productRepository.findById("PROD-1")).thenReturn(Optional.of(Product.builder()
                .id("PROD-1")
                .name("Limited Hoodie")
                .category("DROP")
                .price(new BigDecimal("59000"))
                .build()));
        when(inventoryRepository.findById("PROD-1")).thenReturn(Optional.of(Inventory.builder()
                .productId("PROD-1")
                .totalQuantity(10)
                .availableQuantity(10)
                .reservedQuantity(0)
                .build()));

        ReviewListingRequest request = new ReviewListingRequest();
        request.setOperatorId("ops-1");
        request.setNote("Brand and stock verified.");

        SellerListingResponse response = service.approveListing("LIST-1", request);

        assertEquals(ListingStatus.ACTIVE, response.getStatus());
        assertEquals(SaleEventStatus.LIVE, response.getSaleEventStatus());
        assertEquals("ops-1", response.getReviewedBy());
    }

    @Test
    void rejectionCancelsScheduledSaleEvents() {
        MarketplaceListing listing = MarketplaceListing.builder()
                .listingId("LIST-2")
                .sellerId("SELLER-1")
                .productId("PROD-2")
                .title("Unverified Item")
                .status(ListingStatus.PENDING_REVIEW)
                .build();
        SaleEvent event = SaleEvent.builder()
                .saleEventId("EVT-2")
                .listingId("LIST-2")
                .sellerId("SELLER-1")
                .productId("PROD-2")
                .saleType(SaleType.FIXED_PRICE)
                .status(SaleEventStatus.SCHEDULED)
                .startsAt(java.time.LocalDateTime.now())
                .price(new BigDecimal("49000"))
                .stockQuantity(5)
                .build();
        when(listingRepository.findById("LIST-2")).thenReturn(Optional.of(listing));
        when(listingRepository.save(any(MarketplaceListing.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(saleEventRepository.findByListingIdOrderByStartsAtDesc("LIST-2")).thenReturn(java.util.List.of(event));
        when(saleEventRepository.save(any(SaleEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(productRepository.findById("PROD-2")).thenReturn(Optional.of(Product.builder()
                .id("PROD-2")
                .name("Unverified Item")
                .category("GENERAL")
                .price(new BigDecimal("49000"))
                .build()));
        when(inventoryRepository.findById("PROD-2")).thenReturn(Optional.of(Inventory.builder()
                .productId("PROD-2")
                .totalQuantity(5)
                .availableQuantity(5)
                .reservedQuantity(0)
                .build()));

        ReviewListingRequest request = new ReviewListingRequest();
        request.setOperatorId("ops-1");
        request.setNote("Insufficient seller evidence.");

        SellerListingResponse response = service.rejectListing("LIST-2", request);

        assertEquals(ListingStatus.REJECTED, response.getStatus());
        assertEquals(SaleEventStatus.CANCELLED, response.getSaleEventStatus());
    }

    @Test
    void rejectsListingCreationForInactiveSeller() {
        when(sellerRepository.findById("SELLER-2")).thenReturn(Optional.of(SellerProfile.builder()
                .sellerId("SELLER-2")
                .displayName("Pending Seller")
                .status(SellerStatus.PENDING)
                .verificationStatus(SellerVerificationStatus.UNVERIFIED)
                .build()));

        CreateSellerListingRequest request = new CreateSellerListingRequest();
        request.setName("Limited Watch");
        request.setCategory("AUCTION");
        request.setPrice(new BigDecimal("1000000"));
        request.setQuantity(1);

        assertThrows(IllegalArgumentException.class, () -> service.createListing("SELLER-2", request));
    }
}

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
import com.example.payment.presentation.dto.request.CreateSellerListingRequest;
import com.example.payment.presentation.dto.request.ReviewListingRequest;
import com.example.payment.presentation.dto.response.SellerListingResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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

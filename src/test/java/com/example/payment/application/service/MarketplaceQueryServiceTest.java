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
import com.example.payment.presentation.dto.response.MarketplaceEventResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketplaceQueryServiceTest {

    private final SaleEventRepository saleEventRepository = mock(SaleEventRepository.class);
    private final MarketplaceListingRepository listingRepository = mock(MarketplaceListingRepository.class);
    private final SellerProfileRepository sellerRepository = mock(SellerProfileRepository.class);
    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final InventoryRepository inventoryRepository = mock(InventoryRepository.class);

    private final MarketplaceQueryService service = new MarketplaceQueryService(
            saleEventRepository,
            listingRepository,
            sellerRepository,
            productRepository,
            inventoryRepository
    );

    @Test
    void returnsActiveMarketplaceEventsWithInventoryAndSellerContext() {
        SaleEvent event = SaleEvent.builder()
                .saleEventId("EVT-DRAW-NIKE")
                .listingId("LIST-DRAW-NIKE")
                .sellerId("SELLER-EVERYSALE-CURATED")
                .productId("DRAW-NIKE")
                .saleType(SaleType.RAFFLE)
                .status(SaleEventStatus.LIVE)
                .startsAt(LocalDateTime.now().minusHours(1))
                .endsAt(LocalDateTime.now().plusDays(7))
                .price(new BigDecimal("239000.00"))
                .stockQuantity(10)
                .build();

        when(saleEventRepository.findByStatusInOrderByStartsAtAsc(List.of(SaleEventStatus.LIVE, SaleEventStatus.SCHEDULED)))
                .thenReturn(List.of(event));
        when(listingRepository.findById("LIST-DRAW-NIKE")).thenReturn(Optional.of(MarketplaceListing.builder()
                .listingId("LIST-DRAW-NIKE")
                .sellerId("SELLER-EVERYSALE-CURATED")
                .productId("DRAW-NIKE")
                .title("나이키 조던 드로우")
                .description("한정 수량 드로우")
                .status(ListingStatus.ACTIVE)
                .build()));
        when(sellerRepository.findById("SELLER-EVERYSALE-CURATED")).thenReturn(Optional.of(SellerProfile.builder()
                .sellerId("SELLER-EVERYSALE-CURATED")
                .displayName("EverySale Curated")
                .status(SellerStatus.ACTIVE)
                .verificationStatus(SellerVerificationStatus.VERIFIED)
                .build()));
        when(productRepository.findById("DRAW-NIKE")).thenReturn(Optional.of(Product.builder()
                .id("DRAW-NIKE")
                .name("Air Jordan 1")
                .description("Limited sneakers")
                .price(new BigDecimal("239000.00"))
                .category("SNEAKERS")
                .build()));
        when(inventoryRepository.findById("DRAW-NIKE")).thenReturn(Optional.of(Inventory.builder()
                .productId("DRAW-NIKE")
                .totalQuantity(10)
                .availableQuantity(7)
                .reservedQuantity(3)
                .build()));

        List<MarketplaceEventResponse> events = service.getEvents(null, null, "조던", "priceAsc");

        assertEquals(1, events.size());
        MarketplaceEventResponse response = events.get(0);
        assertEquals("EVT-DRAW-NIKE", response.getSaleEventId());
        assertEquals("EverySale Curated", response.getSellerName());
        assertEquals(SaleType.RAFFLE, response.getSaleType());
        assertEquals(7, response.getAvailableQuantity());
    }

    @Test
    void rejectsInvalidSaleTypeFilter() {
        assertThrows(IllegalArgumentException.class,
                () -> service.getEvents(null, "FLASH_SALE", null, null));
    }
}

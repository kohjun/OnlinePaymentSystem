package com.example.payment.application.service;

import com.example.payment.domain.model.inventory.Inventory;
import com.example.payment.domain.model.marketplace.ListingStatus;
import com.example.payment.domain.model.marketplace.MarketplaceListing;
import com.example.payment.domain.model.marketplace.RaffleEntry;
import com.example.payment.domain.model.marketplace.RaffleEntryStatus;
import com.example.payment.domain.model.marketplace.RaffleWinner;
import com.example.payment.domain.model.marketplace.SaleEvent;
import com.example.payment.domain.model.marketplace.SaleEventStatus;
import com.example.payment.domain.model.marketplace.SaleType;
import com.example.payment.domain.repository.InventoryRepository;
import com.example.payment.domain.repository.MarketplaceListingRepository;
import com.example.payment.domain.repository.RaffleEntryRepository;
import com.example.payment.domain.repository.RaffleWinnerRepository;
import com.example.payment.domain.repository.SaleEventRepository;
import com.example.payment.presentation.dto.request.RaffleDrawRequest;
import com.example.payment.presentation.dto.request.RaffleEntryRequest;
import com.example.payment.presentation.dto.response.RaffleEntryResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RaffleServiceTest {

    private final SaleEventRepository saleEventRepository = mock(SaleEventRepository.class);
    private final MarketplaceListingRepository listingRepository = mock(MarketplaceListingRepository.class);
    private final InventoryRepository inventoryRepository = mock(InventoryRepository.class);
    private final RaffleEntryRepository entryRepository = mock(RaffleEntryRepository.class);
    private final RaffleWinnerRepository winnerRepository = mock(RaffleWinnerRepository.class);
    private final CompleteReservationGateway completeReservationGateway = mock(CompleteReservationGateway.class);
    private final MarketplaceOrderService marketplaceOrderService = mock(MarketplaceOrderService.class);
    private final CheckoutPricingService checkoutPricingService = mock(CheckoutPricingService.class);

    private final RaffleService service = new RaffleService(
            saleEventRepository,
            listingRepository,
            inventoryRepository,
            entryRepository,
            winnerRepository,
            completeReservationGateway,
            marketplaceOrderService,
            checkoutPricingService
    );

    @Test
    void entersLiveRaffleOnce() {
        stubLiveRaffle();
        when(entryRepository.findBySaleEventIdAndCustomerId("EVT-RAFFLE", "CUST-1")).thenReturn(Optional.empty());
        when(entryRepository.save(any(RaffleEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RaffleEntryRequest request = new RaffleEntryRequest();
        request.setCustomerId("CUST-1");

        RaffleEntryResponse response = service.enter("EVT-RAFFLE", request);

        assertEquals("EVT-RAFFLE", response.getSaleEventId());
        assertEquals("CUST-1", response.getCustomerId());
        assertEquals(RaffleEntryStatus.ENTERED, response.getStatus());
    }

    @Test
    void rejectsDuplicateRaffleEntry() {
        stubLiveRaffle();
        when(entryRepository.findBySaleEventIdAndCustomerId("EVT-RAFFLE", "CUST-1"))
                .thenReturn(Optional.of(RaffleEntry.builder().entryId("ENTRY-1").build()));

        RaffleEntryRequest request = new RaffleEntryRequest();
        request.setCustomerId("CUST-1");

        MarketplaceCheckoutException ex = assertThrows(MarketplaceCheckoutException.class,
                () -> service.enter("EVT-RAFFLE", request));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    void drawCreatesPendingWinnersWithinAvailableInventory() {
        stubLiveRaffle();
        when(inventoryRepository.findById("PROD-RAFFLE")).thenReturn(Optional.of(Inventory.builder()
                .productId("PROD-RAFFLE")
                .totalQuantity(10)
                .availableQuantity(2)
                .reservedQuantity(0)
                .build()));
        when(winnerRepository.countBySaleEventId("EVT-RAFFLE")).thenReturn(0L);
        when(entryRepository.findBySaleEventIdAndStatusOrderByCreatedAtAsc("EVT-RAFFLE", RaffleEntryStatus.ENTERED))
                .thenReturn(List.of(entry("ENTRY-1", "CUST-1"), entry("ENTRY-2", "CUST-2"), entry("ENTRY-3", "CUST-3")));
        when(entryRepository.save(any(RaffleEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(winnerRepository.save(any(RaffleWinner.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(winnerRepository.findBySaleEventIdOrderByCreatedAtAsc("EVT-RAFFLE")).thenReturn(List.of());

        RaffleDrawRequest request = new RaffleDrawRequest();
        request.setWinnerCount(3);
        request.setSeed("fixed-seed");
        request.setOperatorId("operator");

        service.draw("EVT-RAFFLE", request);

        verify(winnerRepository, times(2)).save(any(RaffleWinner.class));
    }

    private void stubLiveRaffle() {
        when(saleEventRepository.findById("EVT-RAFFLE")).thenReturn(Optional.of(SaleEvent.builder()
                .saleEventId("EVT-RAFFLE")
                .listingId("LIST-RAFFLE")
                .sellerId("SELLER-1")
                .productId("PROD-RAFFLE")
                .saleType(SaleType.RAFFLE)
                .status(SaleEventStatus.LIVE)
                .startsAt(LocalDateTime.now().minusMinutes(5))
                .endsAt(LocalDateTime.now().plusDays(1))
                .price(new BigDecimal("239000"))
                .stockQuantity(10)
                .build()));
        when(listingRepository.findById("LIST-RAFFLE")).thenReturn(Optional.of(MarketplaceListing.builder()
                .listingId("LIST-RAFFLE")
                .sellerId("SELLER-1")
                .productId("PROD-RAFFLE")
                .title("Limited Raffle")
                .status(ListingStatus.ACTIVE)
                .build()));
    }

    private RaffleEntry entry(String entryId, String customerId) {
        return RaffleEntry.builder()
                .entryId(entryId)
                .saleEventId("EVT-RAFFLE")
                .customerId(customerId)
                .status(RaffleEntryStatus.ENTERED)
                .createdAt(LocalDateTime.now())
                .build();
    }
}

package com.example.payment.application.service;

import com.example.payment.domain.model.inventory.Inventory;
import com.example.payment.domain.model.marketplace.ListingStatus;
import com.example.payment.domain.model.marketplace.MarketplaceListing;
import com.example.payment.domain.model.marketplace.RaffleCheckoutStatus;
import com.example.payment.domain.model.marketplace.RaffleEntry;
import com.example.payment.domain.model.marketplace.RaffleEntryStatus;
import com.example.payment.domain.model.marketplace.RaffleWinner;
import com.example.payment.domain.model.marketplace.SaleEvent;
import com.example.payment.domain.model.marketplace.SaleEventStatus;
import com.example.payment.domain.model.marketplace.SaleType;
import com.example.payment.domain.repository.InventoryRepository;
import com.example.payment.domain.repository.MarketplaceListingRepository;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.util.ReflectionTestUtils;
import com.example.payment.infrastructure.security.CommerceActor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private final InventoryHoldService inventoryHoldService = mock(InventoryHoldService.class);
    private final CommercePolicyService commercePolicyService = mock(CommercePolicyService.class);
    private final MarketplaceRealtimeService marketplaceRealtimeService = mock(MarketplaceRealtimeService.class);
    private final RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final RaffleService service = new RaffleService(
            saleEventRepository,
            listingRepository,
            inventoryRepository,
            entryRepository,
            winnerRepository,
            inventoryHoldService,
            commercePolicyService,
            marketplaceRealtimeService,
            redisTemplate,
            objectMapper
    );

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "winnerCheckoutTtlMinutes", 30L);
        org.springframework.data.redis.core.ValueOperations valueOps = mock(org.springframework.data.redis.core.ValueOperations.class);
        org.springframework.data.redis.core.SetOperations setOps = mock(org.springframework.data.redis.core.SetOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
    }

    @Test
    void entersLiveRaffleOnce() {
        stubLiveRaffle();
        when(entryRepository.findBySaleEventIdAndCustomerId("EVT-RAFFLE", "CUST-1")).thenReturn(Optional.empty());
        when(entryRepository.insertIfAbsent(
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        )).thenReturn(1);
        when(winnerRepository.findBySaleEventIdOrderByCreatedAtAsc("EVT-RAFFLE")).thenReturn(List.of());

        RaffleEntryRequest request = new RaffleEntryRequest();
        request.setCustomerId("CUST-1");

        RaffleEntryResponse response = service.enter(
                "EVT-RAFFLE", new CommerceActor("USER-CUST-1", "CUST-1"), request);

        assertEquals("EVT-RAFFLE", response.getSaleEventId());
        assertEquals("CUST-1", response.getCustomerId());
        assertEquals(RaffleEntryStatus.ENTERED, response.getStatus());
    }

    @Test
    void duplicateRaffleEntryReturnsExistingEntry() {
        stubLiveRaffle();
        when(entryRepository.findBySaleEventIdAndCustomerId("EVT-RAFFLE", "CUST-1"))
                .thenReturn(Optional.of(entry("ENTRY-1", "CUST-1")));

        RaffleEntryRequest request = new RaffleEntryRequest();
        request.setCustomerId("CUST-1");

        RaffleEntryResponse response = service.enter(
                "EVT-RAFFLE", new CommerceActor("USER-CUST-1", "CUST-1"), request);

        assertEquals("ENTRY-1", response.getEntryId());
        assertEquals(RaffleEntryStatus.ENTERED, response.getStatus());
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
        when(winnerRepository.findBySaleEventIdOrderByCreatedAtAsc("EVT-RAFFLE")).thenReturn(List.of(
                RaffleWinner.builder()
                        .winnerId("WIN-1")
                        .saleEventId("EVT-RAFFLE")
                        .entryId("ENTRY-1")
                        .customerId("CUST-1")
                        .checkoutStatus(RaffleCheckoutStatus.PENDING)
                        .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                        .build(),
                RaffleWinner.builder()
                        .winnerId("WIN-2")
                        .saleEventId("EVT-RAFFLE")
                        .entryId("ENTRY-2")
                        .customerId("CUST-2")
                        .checkoutStatus(RaffleCheckoutStatus.PENDING)
                        .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                        .build()
        ));

        RaffleDrawRequest request = new RaffleDrawRequest();
        request.setWinnerCount(3);
        request.setSeed("fixed-seed");
        request.setOperatorId("operator");

        service.draw("EVT-RAFFLE", request);

        verify(winnerRepository, times(2)).save(any(RaffleWinner.class));
        verify(inventoryHoldService, times(2)).hold(
                any(),
                org.mockito.ArgumentMatchers.eq("PROD-RAFFLE"),
                any(),
                org.mockito.ArgumentMatchers.eq(1),
                any(),
                org.mockito.ArgumentMatchers.eq("RAFFLE_WINNER"),
                org.mockito.ArgumentMatchers.eq("RAFFLE_WINNER"),
                any()
        );
    }

    @Test
    void dueRaffleWithoutEntriesClosesCleanly() {
        SaleEvent event = SaleEvent.builder()
                .saleEventId("EVT-RAFFLE")
                .listingId("LIST-RAFFLE")
                .sellerId("SELLER-1")
                .productId("PROD-RAFFLE")
                .saleType(SaleType.RAFFLE)
                .status(SaleEventStatus.LIVE)
                .startsAt(LocalDateTime.now(ZoneOffset.UTC).minusHours(2))
                .endsAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1))
                .price(new BigDecimal("239000"))
                .stockQuantity(10)
                .build();
        when(saleEventRepository.findByIdForUpdate("EVT-RAFFLE")).thenReturn(Optional.of(event));
        when(saleEventRepository.findById("EVT-RAFFLE")).thenReturn(Optional.of(event));
        when(entryRepository.findBySaleEventIdAndStatusOrderByCreatedAtAsc(
                "EVT-RAFFLE", RaffleEntryStatus.ENTERED)).thenReturn(List.of());
        when(winnerRepository.findBySaleEventIdOrderByCreatedAtAsc("EVT-RAFFLE")).thenReturn(List.of());
        when(saleEventRepository.save(any(SaleEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.drawDueRaffle("EVT-RAFFLE");

        assertEquals(SaleEventStatus.ENDED, event.getStatus());
        assertEquals(SaleEventStatus.ENDED, response.getEventStatus());
        assertEquals(true, response.getDrawn());
        verify(marketplaceRealtimeService).publishRaffle(
                "EVT-RAFFLE", "raffle-status", response);
    }

    private void stubLiveRaffle() {
        SaleEvent event = SaleEvent.builder()
                .saleEventId("EVT-RAFFLE")
                .listingId("LIST-RAFFLE")
                .sellerId("SELLER-1")
                .productId("PROD-RAFFLE")
                .saleType(SaleType.RAFFLE)
                .status(SaleEventStatus.LIVE)
                .startsAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5))
                .endsAt(LocalDateTime.now(ZoneOffset.UTC).plusDays(1))
                .price(new BigDecimal("239000"))
                .stockQuantity(10)
                .build();
        when(saleEventRepository.findById("EVT-RAFFLE")).thenReturn(Optional.of(event));
        when(saleEventRepository.findByIdForUpdate("EVT-RAFFLE")).thenReturn(Optional.of(event));
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
                .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();
    }
}

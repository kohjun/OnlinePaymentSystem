package com.example.payment.application.service;

import com.example.payment.domain.model.marketplace.AuctionBid;
import com.example.payment.domain.model.marketplace.AuctionBidStatus;
import com.example.payment.domain.model.marketplace.AuctionSettlement;
import com.example.payment.domain.model.marketplace.AuctionSettlementStatus;
import com.example.payment.domain.model.marketplace.ListingStatus;
import com.example.payment.domain.model.marketplace.MarketplaceListing;
import com.example.payment.domain.model.marketplace.SaleEvent;
import com.example.payment.domain.model.marketplace.SaleEventStatus;
import com.example.payment.domain.model.marketplace.SaleType;
import com.example.payment.domain.repository.AuctionBidRepository;
import com.example.payment.domain.repository.AuctionSettlementRepository;
import com.example.payment.domain.repository.MarketplaceListingRepository;
import com.example.payment.domain.repository.SaleEventRepository;
import com.example.payment.presentation.dto.request.AuctionBidRequest;
import com.example.payment.presentation.dto.response.AuctionBidResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

class AuctionServiceTest {

    private final SaleEventRepository saleEventRepository = mock(SaleEventRepository.class);
    private final MarketplaceListingRepository listingRepository = mock(MarketplaceListingRepository.class);
    private final AuctionBidRepository bidRepository = mock(AuctionBidRepository.class);
    private final AuctionSettlementRepository settlementRepository = mock(AuctionSettlementRepository.class);
    private final InventoryHoldService inventoryHoldService = mock(InventoryHoldService.class);
    private final MarketplaceRealtimeService marketplaceRealtimeService = mock(MarketplaceRealtimeService.class);
    private final RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AuctionService service = new AuctionService(
            saleEventRepository,
            listingRepository,
            bidRepository,
            settlementRepository,
            inventoryHoldService,
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
    void placeBidRequiresMinimumIncrement() {
        stubLiveAuction();
        when(bidRepository.findFirstBySaleEventIdOrderByBidAmountDescCreatedAtAsc("EVT-AUCTION"))
                .thenReturn(Optional.of(existingBid(new BigDecimal("8500000"))));

        AuctionBidRequest request = new AuctionBidRequest();
        request.setCustomerId("CUST-2");
        request.setBidAmount(new BigDecimal("8550000"));

        MarketplaceCheckoutException ex = assertThrows(MarketplaceCheckoutException.class,
                () -> service.placeBid("EVT-AUCTION", request));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
    }

    @Test
    void placeBidPersistsWinningBidAndOutbidsPreviousBid() {
        stubLiveAuction();
        when(bidRepository.findFirstBySaleEventIdOrderByBidAmountDescCreatedAtAsc("EVT-AUCTION"))
                .thenReturn(Optional.of(existingBid(new BigDecimal("8500000"))));
        when(bidRepository.save(any(AuctionBid.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuctionBidRequest request = new AuctionBidRequest();
        request.setCustomerId("CUST-2");
        request.setBidAmount(new BigDecimal("8600000"));

        AuctionBidResponse response = service.placeBid("EVT-AUCTION", request);

        assertEquals("CUST-2", response.getCustomerId());
        assertEquals(AuctionBidStatus.WINNING, response.getStatus());
        assertEquals(new BigDecimal("8600000"), response.getBidAmount());
    }

    @Test
    void closeAboveReserveAllocatesInventoryBeforeCreatingSettlement() {
        SaleEvent event = stubLiveAuction();
        event.setReservePrice(new BigDecimal("9000000"));
        AuctionBid highest = existingBid(new BigDecimal("9500000"));
        when(bidRepository.findFirstBySaleEventIdOrderByBidAmountDescCreatedAtAsc("EVT-AUCTION"))
                .thenReturn(Optional.of(highest));
        when(bidRepository.findTop10BySaleEventIdOrderByBidAmountDescCreatedAtAsc("EVT-AUCTION"))
                .thenReturn(List.of(highest));
        when(settlementRepository.findFirstBySaleEventIdOrderByOfferSequenceDesc("EVT-AUCTION"))
                .thenReturn(Optional.empty());
        when(settlementRepository.save(any(AuctionSettlement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.close("EVT-AUCTION");

        verify(inventoryHoldService).hold(
                any(),
                org.mockito.ArgumentMatchers.eq("PROD-AUCTION"),
                org.mockito.ArgumentMatchers.eq("CUST-1"),
                org.mockito.ArgumentMatchers.eq(1),
                any(),
                org.mockito.ArgumentMatchers.eq("AUCTION_WINNER"),
                org.mockito.ArgumentMatchers.eq("AUCTION_SETTLEMENT"),
                any()
        );
        verify(settlementRepository).save(any(AuctionSettlement.class));
        assertEquals(com.example.payment.domain.model.marketplace.AuctionCloseReason.SOLD, event.getCloseReason());
    }

    @Test
    void closeBelowReserveEndsWithoutSettlement() {
        SaleEvent event = stubLiveAuction();
        event.setReservePrice(new BigDecimal("9000000"));
        AuctionBid highest = existingBid(new BigDecimal("8800000"));
        when(bidRepository.findFirstBySaleEventIdOrderByBidAmountDescCreatedAtAsc("EVT-AUCTION"))
                .thenReturn(Optional.of(highest));
        when(bidRepository.findTop10BySaleEventIdOrderByBidAmountDescCreatedAtAsc("EVT-AUCTION"))
                .thenReturn(List.of(highest));
        when(settlementRepository.findFirstBySaleEventIdOrderByOfferSequenceDesc("EVT-AUCTION")).thenReturn(Optional.empty());

        var response = service.close("EVT-AUCTION");

        assertEquals(com.example.payment.domain.model.marketplace.AuctionCloseReason.RESERVE_NOT_MET,
                response.getCloseReason());
        assertEquals(false, response.getReserveMet());
        assertEquals(AuctionBidStatus.RESERVE_NOT_MET, highest.getStatus());
        verify(settlementRepository, never()).save(any(AuctionSettlement.class));
    }

    @Test
    void repeatedCloseOfEndedAuctionNeverCreatesSettlement() {
        SaleEvent event = stubLiveAuction();
        event.setStatus(SaleEventStatus.ENDED);
        event.setCloseReason(com.example.payment.domain.model.marketplace.AuctionCloseReason.NO_BIDS);
        when(bidRepository.findFirstBySaleEventIdOrderByBidAmountDescCreatedAtAsc("EVT-AUCTION"))
                .thenReturn(Optional.of(existingBid(new BigDecimal("9500000"))));
        when(bidRepository.findTop10BySaleEventIdOrderByBidAmountDescCreatedAtAsc("EVT-AUCTION"))
                .thenReturn(List.of());
        when(settlementRepository.findFirstBySaleEventIdOrderByOfferSequenceDesc("EVT-AUCTION")).thenReturn(Optional.empty());

        var response = service.close("EVT-AUCTION");

        assertEquals(com.example.payment.domain.model.marketplace.AuctionCloseReason.NO_BIDS,
                response.getCloseReason());
        verify(settlementRepository, never()).save(any(AuctionSettlement.class));
    }

    private SaleEvent stubLiveAuction() {
        SaleEvent event = SaleEvent.builder()
                .saleEventId("EVT-AUCTION")
                .listingId("LIST-AUCTION")
                .sellerId("SELLER-1")
                .productId("PROD-AUCTION")
                .saleType(SaleType.AUCTION)
                .status(SaleEventStatus.LIVE)
                .startsAt(LocalDateTime.now().minusMinutes(5))
                .endsAt(LocalDateTime.now().plusHours(1))
                .price(new BigDecimal("8500000"))
                .minBidIncrement(new BigDecimal("100000"))
                .stockQuantity(1)
                .build();
        when(saleEventRepository.findById("EVT-AUCTION")).thenReturn(Optional.of(event));
        when(saleEventRepository.findByIdForUpdate("EVT-AUCTION")).thenReturn(Optional.of(event));
        when(listingRepository.findById("LIST-AUCTION")).thenReturn(Optional.of(MarketplaceListing.builder()
                .listingId("LIST-AUCTION")
                .sellerId("SELLER-1")
                .productId("PROD-AUCTION")
                .title("Vintage Watch")
                .status(ListingStatus.ACTIVE)
                .build()));
        return event;
    }

    private AuctionBid existingBid(BigDecimal amount) {
        return AuctionBid.builder()
                .bidId("BID-1")
                .saleEventId("EVT-AUCTION")
                .customerId("CUST-1")
                .bidAmount(amount)
                .status(AuctionBidStatus.WINNING)
                .createdAt(LocalDateTime.now())
                .build();
    }
}

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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuctionServiceTest {

    private final SaleEventRepository saleEventRepository = mock(SaleEventRepository.class);
    private final MarketplaceListingRepository listingRepository = mock(MarketplaceListingRepository.class);
    private final AuctionBidRepository bidRepository = mock(AuctionBidRepository.class);
    private final AuctionSettlementRepository settlementRepository = mock(AuctionSettlementRepository.class);
    private final MarketplaceRealtimeService marketplaceRealtimeService = mock(MarketplaceRealtimeService.class);
    private final RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AuctionService service = new AuctionService(
            saleEventRepository,
            listingRepository,
            bidRepository,
            settlementRepository,
            marketplaceRealtimeService,
            redisTemplate,
            objectMapper
    );

    @BeforeEach
    void setUp() {
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

    private void stubLiveAuction() {
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

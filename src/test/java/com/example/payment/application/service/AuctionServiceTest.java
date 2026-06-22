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
import com.example.payment.presentation.dto.request.CompleteReservationRequest;
import com.example.payment.presentation.dto.request.MarketplaceCheckoutRequest;
import com.example.payment.presentation.dto.response.AuctionBidResponse;
import com.example.payment.presentation.dto.response.CompleteReservationResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuctionServiceTest {

    private final SaleEventRepository saleEventRepository = mock(SaleEventRepository.class);
    private final MarketplaceListingRepository listingRepository = mock(MarketplaceListingRepository.class);
    private final AuctionBidRepository bidRepository = mock(AuctionBidRepository.class);
    private final AuctionSettlementRepository settlementRepository = mock(AuctionSettlementRepository.class);
    private final CompleteReservationGateway completeReservationGateway = mock(CompleteReservationGateway.class);
    private final MarketplaceOrderService marketplaceOrderService = mock(MarketplaceOrderService.class);

    private final AuctionService service = new AuctionService(
            saleEventRepository,
            listingRepository,
            bidRepository,
            settlementRepository,
            completeReservationGateway,
            marketplaceOrderService
    );

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
    void winnerCheckoutMarksSettlementPaidAndCreatesHeldPayout() {
        stubLiveAuction();
        when(settlementRepository.findBySaleEventIdAndCustomerIdAndStatus(
                "EVT-AUCTION", "CUST-1", AuctionSettlementStatus.AWAITING_PAYMENT
        )).thenReturn(Optional.of(AuctionSettlement.builder()
                .settlementId("SET-1")
                .saleEventId("EVT-AUCTION")
                .winningBidId("BID-1")
                .customerId("CUST-1")
                .sellerId("SELLER-1")
                .amount(new BigDecimal("9000000"))
                .status(AuctionSettlementStatus.AWAITING_PAYMENT)
                .createdAt(LocalDateTime.now())
                .build()));
        when(completeReservationGateway.processCompleteReservation(any()))
                .thenReturn(CompleteReservationResponse.success(
                        "RES-1", "ORD-1", "PAY-1", "TX-1",
                        "PROD-AUCTION", 1, new BigDecimal("9000000"), "KRW"
                ));
        when(settlementRepository.save(any(AuctionSettlement.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MarketplaceCheckoutRequest request = new MarketplaceCheckoutRequest();
        request.setCustomerId("CUST-1");
        request.setQuantity(1);
        request.setIdempotencyKey("IDEMP-1");
        request.setPaymentInfo(CompleteReservationRequest.PaymentInfo.builder()
                .amount(new BigDecimal("1"))
                .currency("KRW")
                .paymentMethod("CREDIT_CARD")
                .build());

        CompleteReservationResponse response = service.winnerCheckout("EVT-AUCTION", request);

        assertEquals("SUCCESS", response.getStatus());
        verify(marketplaceOrderService).recordCheckout(any(), any(), any(), any(), any(), eq("SET-1"), eq(new BigDecimal("9000000")));
    }

    private void stubLiveAuction() {
        when(saleEventRepository.findById("EVT-AUCTION")).thenReturn(Optional.of(SaleEvent.builder()
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
                .build()));
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

package com.example.payment.application.service;

import com.example.payment.domain.model.marketplace.AuctionBid;
import com.example.payment.domain.model.marketplace.AuctionBidStatus;
import com.example.payment.domain.model.marketplace.AuctionSettlement;
import com.example.payment.domain.model.marketplace.AuctionSettlementStatus;
import com.example.payment.domain.model.marketplace.ListingStatus;
import com.example.payment.domain.model.marketplace.MarketplaceCheckoutType;
import com.example.payment.domain.model.marketplace.MarketplaceListing;
import com.example.payment.domain.model.marketplace.SaleEvent;
import com.example.payment.domain.model.marketplace.SaleEventStatus;
import com.example.payment.domain.model.marketplace.SaleType;
import com.example.payment.domain.repository.AuctionBidRepository;
import com.example.payment.domain.repository.AuctionSettlementRepository;
import com.example.payment.domain.repository.MarketplaceListingRepository;
import com.example.payment.domain.repository.SaleEventRepository;
import com.example.payment.presentation.dto.request.AuctionBidRequest;
import com.example.payment.presentation.dto.request.MarketplaceCheckoutRequest;
import com.example.payment.presentation.dto.request.CompleteReservationRequest;
import com.example.payment.presentation.dto.response.AuctionBidResponse;
import com.example.payment.presentation.dto.response.AuctionStatusResponse;
import com.example.payment.presentation.dto.response.CompleteReservationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuctionService {

    private final SaleEventRepository saleEventRepository;
    private final MarketplaceListingRepository marketplaceListingRepository;
    private final AuctionBidRepository auctionBidRepository;
    private final AuctionSettlementRepository auctionSettlementRepository;
    private final CompleteReservationGateway completeReservationGateway;
    private final MarketplaceOrderService marketplaceOrderService;

    @Transactional
    public AuctionBidResponse placeBid(String eventId, AuctionBidRequest request) {
        SaleEvent event = requireAuctionEvent(eventId);
        requireLiveAuction(event);
        requireActiveListing(event);

        AuctionBid currentHighest = auctionBidRepository.findFirstBySaleEventIdOrderByBidAmountDescCreatedAtAsc(eventId)
                .orElse(null);
        BigDecimal currentBid = currentHighest != null ? currentHighest.getBidAmount() : event.getPrice();
        BigDecimal increment = event.getMinBidIncrement() != null ? event.getMinBidIncrement() : new BigDecimal("1000");
        BigDecimal minimumBid = currentBid.add(increment);
        if (request.getBidAmount().compareTo(minimumBid) < 0) {
            throw new MarketplaceCheckoutException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "현재 입찰 가능 최소 금액은 " + minimumBid + "입니다.");
        }

        if (currentHighest != null) {
            currentHighest.setStatus(AuctionBidStatus.OUTBID);
            auctionBidRepository.save(currentHighest);
        }

        AuctionBid saved = auctionBidRepository.save(AuctionBid.builder()
                .bidId("BID-" + shortId())
                .saleEventId(eventId)
                .customerId(request.getCustomerId())
                .bidAmount(request.getBidAmount())
                .status(AuctionBidStatus.WINNING)
                .createdAt(LocalDateTime.now())
                .build());

        if (event.getEndsAt() != null && event.getEndsAt().isBefore(LocalDateTime.now().plusSeconds(30))) {
            event.setEndsAt(LocalDateTime.now().plusSeconds(30));
            saleEventRepository.save(event);
        }

        return toBidResponse(saved);
    }

    @Transactional(readOnly = true)
    public AuctionStatusResponse status(String eventId) {
        SaleEvent event = requireAuctionEvent(eventId);
        AuctionBid highest = auctionBidRepository.findFirstBySaleEventIdOrderByBidAmountDescCreatedAtAsc(eventId)
                .orElse(null);
        BigDecimal highestBid = highest != null ? highest.getBidAmount() : event.getPrice();
        BigDecimal increment = event.getMinBidIncrement() != null ? event.getMinBidIncrement() : new BigDecimal("1000");
        AuctionSettlement settlement = auctionSettlementRepository.findBySaleEventId(eventId).orElse(null);

        return AuctionStatusResponse.builder()
                .saleEventId(eventId)
                .eventStatus(event.getStatus())
                .highestBid(highestBid)
                .highestBidder(highest != null ? highest.getCustomerId() : null)
                .minNextBid(highestBid.add(increment))
                .settlementStatus(settlement != null ? settlement.getStatus() : null)
                .endsAt(event.getEndsAt())
                .history(auctionBidRepository.findTop10BySaleEventIdOrderByBidAmountDescCreatedAtAsc(eventId)
                        .stream()
                        .map(this::toBidResponse)
                        .toList())
                .build();
    }

    @Transactional
    public AuctionStatusResponse close(String eventId) {
        SaleEvent event = requireAuctionEvent(eventId);
        AuctionBid highest = auctionBidRepository.findFirstBySaleEventIdOrderByBidAmountDescCreatedAtAsc(eventId)
                .orElseThrow(() -> new MarketplaceCheckoutException(HttpStatus.CONFLICT, "입찰 내역이 없어 경매를 마감할 수 없습니다."));

        auctionSettlementRepository.findBySaleEventId(eventId)
                .orElseGet(() -> auctionSettlementRepository.save(AuctionSettlement.builder()
                        .settlementId("AUCTSET-" + shortId())
                        .saleEventId(eventId)
                        .winningBidId(highest.getBidId())
                        .customerId(highest.getCustomerId())
                        .sellerId(event.getSellerId())
                        .amount(highest.getBidAmount())
                        .status(AuctionSettlementStatus.AWAITING_PAYMENT)
                        .createdAt(LocalDateTime.now())
                        .build()));

        event.setStatus(SaleEventStatus.ENDED);
        event.setEndsAt(LocalDateTime.now());
        saleEventRepository.save(event);
        return status(eventId);
    }

    @Transactional
    public CompleteReservationResponse winnerCheckout(String eventId, MarketplaceCheckoutRequest request) {
        SaleEvent event = requireAuctionEvent(eventId);
        MarketplaceListing listing = requireActiveListing(event);
        AuctionSettlement settlement = auctionSettlementRepository
                .findBySaleEventIdAndCustomerIdAndStatus(eventId, request.getCustomerId(), AuctionSettlementStatus.AWAITING_PAYMENT)
                .orElseThrow(() -> new MarketplaceCheckoutException(HttpStatus.FORBIDDEN, "낙찰자만 checkout 할 수 있습니다."));

        CompleteReservationRequest completeRequest = CompleteReservationRequest.builder()
                .productId(event.getProductId())
                .customerId(request.getCustomerId())
                .quantity(request.getQuantity())
                .clientId(defaultText(request.getClientId(), "marketplace-auction"))
                .seatId(request.getSeatId())
                .idempotencyKey(request.getIdempotencyKey())
                .correlationId(request.getCorrelationId())
                .shippingInfo(request.getShippingInfo())
                .paymentInfo(CompleteReservationRequest.PaymentInfo.builder()
                        .amount(settlement.getAmount())
                        .currency(defaultText(request.getPaymentInfo().getCurrency(), "KRW"))
                        .paymentMethod(request.getPaymentInfo().getPaymentMethod())
                        .merchantId(event.getSellerId())
                        .orderName("Auction winner checkout")
                        .cardNumber(request.getPaymentInfo().getCardNumber())
                        .cardHolderName(request.getPaymentInfo().getCardHolderName())
                        .build())
                .build();

        CompleteReservationResponse response = completeReservationGateway.processCompleteReservation(completeRequest);
        marketplaceOrderService.recordCheckout(
                event,
                listing,
                request,
                response,
                MarketplaceCheckoutType.AUCTION_WINNER,
                settlement.getSettlementId(),
                settlement.getAmount()
        );
        if ("SUCCESS".equals(response.getStatus())) {
            settlement.setStatus(AuctionSettlementStatus.PAID);
            settlement.setPaidAt(LocalDateTime.now());
            auctionSettlementRepository.save(settlement);
        }
        return response;
    }

    public SseEmitter streamStatus(String eventId) {
        SseEmitter emitter = new SseEmitter(10_000L);
        try {
            emitter.send(SseEmitter.event()
                    .name("auction-status")
                    .data(status(eventId)));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    private SaleEvent requireAuctionEvent(String eventId) {
        SaleEvent event = saleEventRepository.findById(eventId)
                .orElseThrow(() -> new MarketplaceCheckoutException(HttpStatus.NOT_FOUND, "Sale event not found: " + eventId));
        if (event.getSaleType() != SaleType.AUCTION) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "AUCTION 이벤트가 아닙니다.");
        }
        return event;
    }

    private void requireLiveAuction(SaleEvent event) {
        LocalDateTime now = LocalDateTime.now();
        if (event.getStatus() != SaleEventStatus.LIVE) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "경매가 진행 중이 아닙니다.");
        }
        if (event.getStartsAt() != null && event.getStartsAt().isAfter(now)) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "경매가 아직 시작되지 않았습니다.");
        }
        if (event.getEndsAt() != null && !event.getEndsAt().isAfter(now)) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "경매가 종료되었습니다.");
        }
    }

    private MarketplaceListing requireActiveListing(SaleEvent event) {
        MarketplaceListing listing = marketplaceListingRepository.findById(event.getListingId())
                .orElseThrow(() -> new MarketplaceCheckoutException(HttpStatus.NOT_FOUND, "Listing not found: " + event.getListingId()));
        if (listing.getStatus() != ListingStatus.ACTIVE) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "Listing is not active.");
        }
        return listing;
    }

    private AuctionBidResponse toBidResponse(AuctionBid bid) {
        return AuctionBidResponse.builder()
                .bidId(bid.getBidId())
                .saleEventId(bid.getSaleEventId())
                .customerId(bid.getCustomerId())
                .bidAmount(bid.getBidAmount())
                .status(bid.getStatus())
                .createdAt(bid.getCreatedAt())
                .build();
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }
}

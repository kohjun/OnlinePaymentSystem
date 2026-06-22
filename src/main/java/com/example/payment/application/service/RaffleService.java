package com.example.payment.application.service;

import com.example.payment.domain.model.inventory.Inventory;
import com.example.payment.domain.model.marketplace.ListingStatus;
import com.example.payment.domain.model.marketplace.MarketplaceCheckoutType;
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
import com.example.payment.domain.repository.RaffleEntryRepository;
import com.example.payment.domain.repository.RaffleWinnerRepository;
import com.example.payment.domain.repository.SaleEventRepository;
import com.example.payment.presentation.dto.request.CompleteReservationRequest;
import com.example.payment.presentation.dto.request.MarketplaceCheckoutRequest;
import com.example.payment.presentation.dto.request.RaffleDrawRequest;
import com.example.payment.presentation.dto.request.RaffleEntryRequest;
import com.example.payment.presentation.dto.response.CompleteReservationResponse;
import com.example.payment.presentation.dto.response.RaffleEntryResponse;
import com.example.payment.presentation.dto.response.RaffleStatusResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RaffleService {

    private final SaleEventRepository saleEventRepository;
    private final MarketplaceListingRepository marketplaceListingRepository;
    private final InventoryRepository inventoryRepository;
    private final RaffleEntryRepository raffleEntryRepository;
    private final RaffleWinnerRepository raffleWinnerRepository;
    private final CompleteReservationGateway completeReservationGateway;
    private final MarketplaceOrderService marketplaceOrderService;
    private final CheckoutPricingService checkoutPricingService;

    @Transactional
    public RaffleEntryResponse enter(String eventId, RaffleEntryRequest request) {
        SaleEvent event = requireRaffleEvent(eventId);
        MarketplaceListing listing = requireActiveListing(event);
        validateRaffleOpen(event, listing);

        raffleEntryRepository.findBySaleEventIdAndCustomerId(eventId, request.getCustomerId())
                .ifPresent(existing -> {
                    throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "이미 응모한 고객입니다.");
                });

        RaffleEntry entry = raffleEntryRepository.save(RaffleEntry.builder()
                .entryId("RFENT-" + shortId())
                .saleEventId(eventId)
                .customerId(request.getCustomerId())
                .status(RaffleEntryStatus.ENTERED)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
        return toEntryResponse(entry);
    }

    @Transactional(readOnly = true)
    public RaffleStatusResponse status(String eventId, String customerId) {
        requireRaffleEvent(eventId);
        List<RaffleWinner> winners = raffleWinnerRepository.findBySaleEventIdOrderByCreatedAtAsc(eventId);
        RaffleWinner customerWinner = customerId == null ? null :
                raffleWinnerRepository.findBySaleEventIdAndCustomerId(eventId, customerId).orElse(null);
        boolean entered = customerId != null &&
                raffleEntryRepository.findBySaleEventIdAndCustomerId(eventId, customerId).isPresent();

        return RaffleStatusResponse.builder()
                .saleEventId(eventId)
                .entryCount(raffleEntryRepository.countBySaleEventId(eventId))
                .winnerCount(winners.size())
                .completedCheckoutCount(raffleWinnerRepository.countBySaleEventIdAndCheckoutStatus(eventId, RaffleCheckoutStatus.COMPLETED))
                .entered(customerId == null ? null : entered)
                .winner(customerId == null ? null : customerWinner != null)
                .checkoutStatus(customerWinner != null ? customerWinner.getCheckoutStatus() : null)
                .winnerCustomerIds(winners.stream().map(RaffleWinner::getCustomerId).toList())
                .build();
    }

    @Transactional
    public RaffleStatusResponse draw(String eventId, RaffleDrawRequest request) {
        SaleEvent event = requireRaffleEvent(eventId);
        requireActiveListing(event);

        if (raffleWinnerRepository.countBySaleEventId(eventId) > 0) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "이미 추첨이 완료된 이벤트입니다.");
        }

        List<RaffleEntry> entries = new ArrayList<>(
                raffleEntryRepository.findBySaleEventIdAndStatusOrderByCreatedAtAsc(eventId, RaffleEntryStatus.ENTERED)
        );
        if (entries.isEmpty()) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "응모자가 없습니다.");
        }

        Inventory inventory = inventoryRepository.findById(event.getProductId())
                .orElseThrow(() -> new MarketplaceCheckoutException(HttpStatus.NOT_FOUND, "Inventory not found: " + event.getProductId()));
        int requestedWinners = request.getWinnerCount() != null ? request.getWinnerCount() : event.getStockQuantity();
        int winnerCount = Math.min(Math.min(requestedWinners, inventory.getAvailableQuantity()), entries.size());
        if (winnerCount <= 0) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "당첨 가능한 재고가 없습니다.");
        }

        String seed = request.getSeed() != null && !request.getSeed().trim().isEmpty()
                ? request.getSeed().trim()
                : UUID.randomUUID().toString();
        Collections.shuffle(entries, new Random(seed.hashCode()));

        List<RaffleEntry> winners = entries.subList(0, winnerCount);
        for (RaffleEntry entry : entries) {
            entry.setStatus(winners.contains(entry) ? RaffleEntryStatus.WINNER : RaffleEntryStatus.NOT_SELECTED);
            raffleEntryRepository.save(entry);
        }

        for (RaffleEntry winner : winners) {
            raffleWinnerRepository.save(RaffleWinner.builder()
                    .winnerId("RFWIN-" + shortId())
                    .saleEventId(eventId)
                    .entryId(winner.getEntryId())
                    .customerId(winner.getCustomerId())
                    .checkoutStatus(RaffleCheckoutStatus.PENDING)
                    .drawSeed(seed)
                    .drawnBy(defaultText(request.getOperatorId(), "system"))
                    .createdAt(LocalDateTime.now())
                    .build());
        }

        return status(eventId, null);
    }

    @Transactional
    public CompleteReservationResponse winnerCheckout(String eventId, MarketplaceCheckoutRequest request) {
        SaleEvent event = requireRaffleEvent(eventId);
        MarketplaceListing listing = requireActiveListing(event);
        RaffleWinner winner = raffleWinnerRepository.findBySaleEventIdAndCustomerId(eventId, request.getCustomerId())
                .orElseThrow(() -> new MarketplaceCheckoutException(HttpStatus.FORBIDDEN, "래플 당첨자만 checkout 할 수 있습니다."));
        if (winner.getCheckoutStatus() != RaffleCheckoutStatus.PENDING) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "이미 checkout 처리된 당첨자입니다.");
        }

        CompleteReservationRequest completeRequest = CompleteReservationRequest.builder()
                .productId(event.getProductId())
                .customerId(request.getCustomerId())
                .quantity(request.getQuantity())
                .clientId(defaultText(request.getClientId(), "marketplace-raffle"))
                .seatId(request.getSeatId())
                .idempotencyKey(request.getIdempotencyKey())
                .correlationId(request.getCorrelationId())
                .shippingInfo(request.getShippingInfo())
                .paymentInfo(CompleteReservationRequest.PaymentInfo.builder()
                        .amount(event.getPrice())
                        .currency(defaultText(request.getPaymentInfo().getCurrency(), "KRW"))
                        .paymentMethod(request.getPaymentInfo().getPaymentMethod())
                        .merchantId(event.getSellerId())
                        .orderName("Raffle winner checkout")
                        .cardNumber(request.getPaymentInfo().getCardNumber())
                        .cardHolderName(request.getPaymentInfo().getCardHolderName())
                        .build())
                .build();

        CheckoutPricingService.PriceSnapshot priceSnapshot =
                checkoutPricingService.applySaleEventPrice(completeRequest, event, false);

        CompleteReservationResponse response = completeReservationGateway.processCompleteReservation(completeRequest);
        marketplaceOrderService.recordCheckout(
                event,
                listing,
                request,
                response,
                MarketplaceCheckoutType.RAFFLE_WINNER,
                winner.getWinnerId(),
                priceSnapshot.getAmount()
        );
        if ("SUCCESS".equals(response.getStatus())) {
            winner.setCheckoutStatus(RaffleCheckoutStatus.COMPLETED);
            winner.setCheckoutCompletedAt(LocalDateTime.now());
            raffleWinnerRepository.save(winner);
        }
        return response;
    }

    private SaleEvent requireRaffleEvent(String eventId) {
        SaleEvent event = saleEventRepository.findById(eventId)
                .orElseThrow(() -> new MarketplaceCheckoutException(HttpStatus.NOT_FOUND, "Sale event not found: " + eventId));
        if (event.getSaleType() != SaleType.RAFFLE) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "RAFFLE 이벤트가 아닙니다.");
        }
        return event;
    }

    private MarketplaceListing requireActiveListing(SaleEvent event) {
        MarketplaceListing listing = marketplaceListingRepository.findById(event.getListingId())
                .orElseThrow(() -> new MarketplaceCheckoutException(HttpStatus.NOT_FOUND, "Listing not found: " + event.getListingId()));
        if (listing.getStatus() != ListingStatus.ACTIVE) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "Listing is not active.");
        }
        return listing;
    }

    private void validateRaffleOpen(SaleEvent event, MarketplaceListing listing) {
        if (listing.getStatus() != ListingStatus.ACTIVE || event.getStatus() != SaleEventStatus.LIVE) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "응모 가능한 상태가 아닙니다.");
        }
        LocalDateTime now = LocalDateTime.now();
        if (event.getStartsAt() != null && event.getStartsAt().isAfter(now)) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "아직 응모가 시작되지 않았습니다.");
        }
        if (event.getEndsAt() != null && !event.getEndsAt().isAfter(now)) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "응모가 마감되었습니다.");
        }
    }

    private RaffleEntryResponse toEntryResponse(RaffleEntry entry) {
        return RaffleEntryResponse.builder()
                .entryId(entry.getEntryId())
                .saleEventId(entry.getSaleEventId())
                .customerId(entry.getCustomerId())
                .status(entry.getStatus())
                .createdAt(entry.getCreatedAt())
                .build();
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }
}

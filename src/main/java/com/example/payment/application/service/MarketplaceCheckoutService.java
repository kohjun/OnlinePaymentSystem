package com.example.payment.application.service;

import com.example.payment.domain.model.inventory.Inventory;
import com.example.payment.domain.model.marketplace.ListingStatus;
import com.example.payment.domain.model.marketplace.MarketplaceCheckoutType;
import com.example.payment.domain.model.marketplace.MarketplaceListing;
import com.example.payment.domain.model.marketplace.SaleEvent;
import com.example.payment.domain.model.marketplace.SaleEventStatus;
import com.example.payment.domain.model.marketplace.SaleType;
import com.example.payment.domain.repository.InventoryRepository;
import com.example.payment.domain.repository.MarketplaceListingRepository;
import com.example.payment.domain.repository.SaleEventRepository;
import com.example.payment.presentation.dto.request.CompleteReservationRequest;
import com.example.payment.presentation.dto.request.MarketplaceCheckoutRequest;
import com.example.payment.presentation.dto.response.CompleteReservationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class MarketplaceCheckoutService {

    private static final Set<SaleType> DIRECT_CHECKOUT_TYPES = Set.of(SaleType.FIXED_PRICE, SaleType.DROP);

    private final SaleEventRepository saleEventRepository;
    private final MarketplaceListingRepository marketplaceListingRepository;
    private final InventoryRepository inventoryRepository;
    private final CompleteReservationGateway completeReservationGateway;
    private final MarketplaceOrderService marketplaceOrderService;
    private final CheckoutPricingService checkoutPricingService;

    public CompleteReservationResponse checkout(String eventId, MarketplaceCheckoutRequest request) {
        SaleEvent event = saleEventRepository.findById(eventId)
                .orElseThrow(() -> new MarketplaceCheckoutException(HttpStatus.NOT_FOUND, "Sale event not found: " + eventId));
        MarketplaceListing listing = marketplaceListingRepository.findById(event.getListingId())
                .orElseThrow(() -> new MarketplaceCheckoutException(HttpStatus.NOT_FOUND, "Listing not found: " + event.getListingId()));

        validateCheckoutEligibility(event, listing, request.getQuantity());

        CompleteReservationRequest completeRequest = CompleteReservationRequest.builder()
                .productId(event.getProductId())
                .customerId(request.getCustomerId())
                .quantity(request.getQuantity())
                .clientId(defaultText(request.getClientId(), "marketplace"))
                .seatId(request.getSeatId())
                .idempotencyKey(request.getIdempotencyKey())
                .correlationId(request.getCorrelationId())
                .shippingInfo(request.getShippingInfo())
                .paymentInfo(CompleteReservationRequest.PaymentInfo.builder()
                        .amount(event.getPrice())
                        .currency(defaultText(request.getPaymentInfo().getCurrency(), "KRW"))
                        .paymentMethod(request.getPaymentInfo().getPaymentMethod())
                        .merchantId(event.getSellerId())
                        .orderName(listing.getTitle())
                        .successUrl(request.getPaymentInfo().getSuccessUrl())
                        .failUrl(request.getPaymentInfo().getFailUrl())
                        .cancelUrl(request.getPaymentInfo().getCancelUrl())
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
                MarketplaceCheckoutType.DIRECT,
                event.getSaleEventId(),
                priceSnapshot.getAmount()
        );
        return response;
    }

    private void validateCheckoutEligibility(SaleEvent event, MarketplaceListing listing, Integer quantity) {
        if (listing.getStatus() != ListingStatus.ACTIVE) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "Listing is not active.");
        }
        if (event.getStatus() != SaleEventStatus.LIVE) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "Sale event is not live.");
        }
        if (!DIRECT_CHECKOUT_TYPES.contains(event.getSaleType())) {
            throw new MarketplaceCheckoutException(
                    HttpStatus.CONFLICT,
                    "This sale type requires a dedicated winner or bid checkout flow: " + event.getSaleType()
            );
        }

        LocalDateTime now = LocalDateTime.now();
        if (event.getStartsAt() != null && event.getStartsAt().isAfter(now)) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "Sale event has not started.");
        }
        if (event.getEndsAt() != null && !event.getEndsAt().isAfter(now)) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "Sale event has ended.");
        }

        Inventory inventory = inventoryRepository.findById(event.getProductId())
                .orElseThrow(() -> new MarketplaceCheckoutException(HttpStatus.NOT_FOUND, "Inventory not found for product: " + event.getProductId()));
        if (inventory.getAvailableQuantity() < quantity) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "SOLD_OUT");
        }
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}

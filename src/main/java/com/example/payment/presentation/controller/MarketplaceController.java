package com.example.payment.presentation.controller;

import com.example.payment.application.service.MarketplaceQueryService;
import com.example.payment.application.service.AuctionService;
import com.example.payment.application.service.MarketplaceCheckoutException;
import com.example.payment.application.service.MarketplaceCheckoutService;
import com.example.payment.application.service.MarketplaceOrderService;
import com.example.payment.application.service.AmountMismatchException;
import com.example.payment.application.service.IdempotencyConflictException;
import com.example.payment.application.service.TossPaymentIntentService;
import com.example.payment.domain.model.marketplace.MarketplaceCheckoutType;
import com.example.payment.infrastructure.security.AuthorizationGuard;
import com.example.payment.presentation.dto.request.AuctionBidRequest;
import com.example.payment.presentation.dto.request.MarketplaceCheckoutRequest;
import com.example.payment.presentation.dto.request.RaffleDrawRequest;
import com.example.payment.presentation.dto.request.RaffleEntryRequest;
import com.example.payment.presentation.dto.response.CompleteReservationResponse;
import com.example.payment.presentation.dto.response.MarketplaceEventResponse;
import com.example.payment.presentation.dto.response.RaffleEntryResponse;
import com.example.payment.presentation.dto.response.RaffleStatusResponse;
import com.example.payment.presentation.dto.response.TossPaymentIntentResponse;
import com.example.payment.application.service.RaffleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/marketplace")
@RequiredArgsConstructor
@Slf4j
public class MarketplaceController {

    private final MarketplaceQueryService marketplaceQueryService;
    private final MarketplaceCheckoutService marketplaceCheckoutService;
    private final RaffleService raffleService;
    private final AuctionService auctionService;
    private final MarketplaceOrderService marketplaceOrderService;
    private final TossPaymentIntentService tossPaymentIntentService;
    private final AuthorizationGuard authorizationGuard;

    @Value("${app.checkout.legacy-marketplace-enabled:false}")
    private boolean legacyMarketplaceCheckoutEnabled;

    @GetMapping("/events")
    public ResponseEntity<?> getEvents(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String saleType,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "startsAt") String sort) {
        try {
            List<MarketplaceEventResponse> events = marketplaceQueryService.getEvents(status, saleType, keyword, sort);
            return ResponseEntity.ok(events);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid marketplace events query: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "FAILED",
                    "message", e.getMessage()
            ));
        }
    }

    @GetMapping("/events/{eventId}")
    public ResponseEntity<MarketplaceEventResponse> getEvent(@PathVariable String eventId) {
        return marketplaceQueryService.getEvent(eventId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/customers/{customerId}/orders")
    public ResponseEntity<?> getCustomerOrders(@PathVariable String customerId) {
        authorizationGuard.requireCustomerAccess(customerId);
        return ResponseEntity.ok(marketplaceOrderService.getCustomerOrders(customerId));
    }

    @PostMapping("/events/{eventId}/checkout")
    public ResponseEntity<?> checkout(
            @PathVariable String eventId,
            @Valid @RequestBody MarketplaceCheckoutRequest request) {
        if (!legacyMarketplaceCheckoutEnabled) {
            return legacyCheckoutGone();
        }
        authorizationGuard.requireCustomerAccess(request.getCustomerId());
        try {
            CompleteReservationResponse response = marketplaceCheckoutService.checkout(eventId, request);
            if ("SUCCESS".equals(response.getStatus())) {
                return ResponseEntity.ok(response);
            }
            if ("PENDING".equals(response.getStatus())) {
                return ResponseEntity.accepted().body(response);
            }
            return ResponseEntity.badRequest().body(response);
        } catch (MarketplaceCheckoutException e) {
            log.warn("Marketplace checkout rejected: eventId={}, reason={}", eventId, e.getMessage());
            return ResponseEntity.status(e.getStatus()).body(Map.of(
                    "status", "FAILED",
                    "message", e.getMessage()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "status", "FAILED",
                    "message", e.getMessage()
            ));
        }
    }

    @PostMapping("/events/{eventId}/checkout/toss/intents")
    public ResponseEntity<?> directCheckoutTossIntent(
            @PathVariable String eventId,
            @Valid @RequestBody MarketplaceCheckoutRequest request) {
        return createMarketplaceTossIntent(eventId, MarketplaceCheckoutType.DIRECT, request);
    }

    @PostMapping("/events/{eventId}/raffle/entries")
    public ResponseEntity<?> enterRaffle(
            @PathVariable String eventId,
            @Valid @RequestBody RaffleEntryRequest request) {
        try {
            authorizationGuard.requireCustomerAccess(request.getCustomerId());
            RaffleEntryResponse response = raffleService.enter(eventId, request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (MarketplaceCheckoutException e) {
            return marketplaceError(e);
        }
    }

    @GetMapping("/events/{eventId}/raffle/status")
    public ResponseEntity<?> getRaffleStatus(
            @PathVariable String eventId,
            @RequestParam(required = false) String customerId) {
        try {
            if (customerId != null && !customerId.isBlank()) {
                authorizationGuard.requireCustomerAccess(customerId);
            }
            RaffleStatusResponse response = raffleService.status(eventId, customerId);
            return ResponseEntity.ok(response);
        } catch (MarketplaceCheckoutException e) {
            return marketplaceError(e);
        }
    }

    @PostMapping("/events/{eventId}/raffle/draw")
    public ResponseEntity<?> drawRaffle(
            @PathVariable String eventId,
            @Valid @RequestBody RaffleDrawRequest request) {
        try {
            return ResponseEntity.ok(raffleService.draw(eventId, request));
        } catch (MarketplaceCheckoutException e) {
            return marketplaceError(e);
        }
    }

    @PostMapping("/events/{eventId}/raffle/winner-checkout")
    public ResponseEntity<?> raffleWinnerCheckout(
            @PathVariable String eventId,
            @Valid @RequestBody MarketplaceCheckoutRequest request) {
        if (!legacyMarketplaceCheckoutEnabled) {
            return legacyCheckoutGone();
        }
        authorizationGuard.requireCustomerAccess(request.getCustomerId());
        try {
            CompleteReservationResponse response = raffleService.winnerCheckout(eventId, request);
            if ("SUCCESS".equals(response.getStatus())) {
                return ResponseEntity.ok(response);
            }
            if ("PENDING".equals(response.getStatus())) {
                return ResponseEntity.accepted().body(response);
            }
            return ResponseEntity.badRequest().body(response);
        } catch (MarketplaceCheckoutException e) {
            return marketplaceError(e);
        }
    }

    @PostMapping("/events/{eventId}/raffle/winner-checkout/toss/intents")
    public ResponseEntity<?> raffleWinnerCheckoutTossIntent(
            @PathVariable String eventId,
            @Valid @RequestBody MarketplaceCheckoutRequest request) {
        return createMarketplaceTossIntent(eventId, MarketplaceCheckoutType.RAFFLE_WINNER, request);
    }

    @PostMapping("/events/{eventId}/bids")
    public ResponseEntity<?> placeAuctionBid(
            @PathVariable String eventId,
            @Valid @RequestBody AuctionBidRequest request) {
        try {
            authorizationGuard.requireCustomerAccess(request.getCustomerId());
            return ResponseEntity.status(HttpStatus.CREATED).body(auctionService.placeBid(eventId, request));
        } catch (MarketplaceCheckoutException e) {
            return marketplaceError(e);
        }
    }

    @GetMapping("/events/{eventId}/auction/status")
    public ResponseEntity<?> getAuctionStatus(@PathVariable String eventId) {
        try {
            return ResponseEntity.ok(auctionService.status(eventId));
        } catch (MarketplaceCheckoutException e) {
            return marketplaceError(e);
        }
    }

    @GetMapping("/events/{eventId}/auction/stream")
    public SseEmitter streamAuctionStatus(@PathVariable String eventId) {
        return auctionService.streamStatus(eventId);
    }

    @PostMapping("/events/{eventId}/auction/close")
    public ResponseEntity<?> closeAuction(@PathVariable String eventId) {
        try {
            return ResponseEntity.ok(auctionService.close(eventId));
        } catch (MarketplaceCheckoutException e) {
            return marketplaceError(e);
        }
    }

    @PostMapping("/events/{eventId}/auction/winner-checkout")
    public ResponseEntity<?> auctionWinnerCheckout(
            @PathVariable String eventId,
            @Valid @RequestBody MarketplaceCheckoutRequest request) {
        if (!legacyMarketplaceCheckoutEnabled) {
            return legacyCheckoutGone();
        }
        authorizationGuard.requireCustomerAccess(request.getCustomerId());
        try {
            CompleteReservationResponse response = auctionService.winnerCheckout(eventId, request);
            if ("SUCCESS".equals(response.getStatus())) {
                return ResponseEntity.ok(response);
            }
            if ("PENDING".equals(response.getStatus())) {
                return ResponseEntity.accepted().body(response);
            }
            return ResponseEntity.badRequest().body(response);
        } catch (MarketplaceCheckoutException e) {
            return marketplaceError(e);
        }
    }

    @PostMapping("/events/{eventId}/auction/winner-checkout/toss/intents")
    public ResponseEntity<?> auctionWinnerCheckoutTossIntent(
            @PathVariable String eventId,
            @Valid @RequestBody MarketplaceCheckoutRequest request) {
        return createMarketplaceTossIntent(eventId, MarketplaceCheckoutType.AUCTION_WINNER, request);
    }

    private ResponseEntity<?> createMarketplaceTossIntent(String eventId,
                                                          MarketplaceCheckoutType checkoutType,
                                                          MarketplaceCheckoutRequest request) {
        try {
            authorizationGuard.requireCustomerAccess(request.getCustomerId());
            TossPaymentIntentResponse response = tossPaymentIntentService.createMarketplaceIntent(eventId, checkoutType, request);
            return ResponseEntity.ok(response);
        } catch (MarketplaceCheckoutException e) {
            return marketplaceError(e);
        } catch (AmountMismatchException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "status", "FAILED",
                    "errorCode", "AMOUNT_MISMATCH",
                    "message", e.getMessage()
            ));
        } catch (IdempotencyConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "status", "FAILED",
                    "errorCode", "IDEMPOTENCY_KEY_CONFLICT",
                    "message", e.getMessage()
            ));
        }
    }

    private ResponseEntity<Map<String, Object>> marketplaceError(MarketplaceCheckoutException e) {
        log.warn("Marketplace request rejected: {}", e.getMessage());
        return ResponseEntity.status(e.getStatus()).body(Map.of(
                "status", "FAILED",
                "message", e.getMessage()
        ));
    }

    private ResponseEntity<Map<String, Object>> legacyCheckoutGone() {
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                "status", "FAILED",
                "errorCode", "LEGACY_MARKETPLACE_CHECKOUT_DISABLED",
                "message", "Legacy marketplace checkout is disabled. Use Toss Payments intent/confirm checkout."
        ));
    }
}

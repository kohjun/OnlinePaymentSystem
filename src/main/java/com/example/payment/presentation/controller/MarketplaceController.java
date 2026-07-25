package com.example.payment.presentation.controller;

import com.example.payment.application.service.MarketplaceQueryService;
import com.example.payment.application.service.AuctionService;
import com.example.payment.application.service.MarketplaceCheckoutException;
import com.example.payment.application.service.MarketplaceOrderService;
import com.example.payment.application.service.AmountMismatchException;
import com.example.payment.application.service.IdempotencyConflictException;
import com.example.payment.application.service.TossPaymentIntentService;
import com.example.payment.application.service.TicketingService;
import com.example.payment.domain.model.marketplace.MarketplaceCheckoutType;
import com.example.payment.infrastructure.security.AuthorizationGuard;
import com.example.payment.infrastructure.security.SecurityAuditService;
import com.example.payment.presentation.dto.request.AuctionBidRequest;
import com.example.payment.presentation.dto.request.MarketplaceCheckoutRequest;
import com.example.payment.presentation.dto.request.RaffleDrawRequest;
import com.example.payment.presentation.dto.request.RaffleEntryRequest;
import com.example.payment.presentation.dto.response.MarketplaceEventResponse;
import com.example.payment.presentation.dto.response.RaffleEntryResponse;
import com.example.payment.presentation.dto.response.RaffleStatusResponse;
import com.example.payment.presentation.dto.response.TossPaymentIntentResponse;
import com.example.payment.presentation.dto.response.TicketSeatHoldResponse;
import com.example.payment.presentation.dto.response.TicketSeatMapResponse;
import com.example.payment.application.service.RaffleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final RaffleService raffleService;
    private final AuctionService auctionService;
    private final MarketplaceOrderService marketplaceOrderService;
    private final TossPaymentIntentService tossPaymentIntentService;
    private final TicketingService ticketingService;
    private final AuthorizationGuard authorizationGuard;
    private final SecurityAuditService securityAuditService;

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
    public ResponseEntity<?> getCustomerOrders(
            @PathVariable String customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        authorizationGuard.requireCustomerAccess(customerId);
        return ResponseEntity.ok(marketplaceOrderService.getCustomerOrders(customerId, page, size));
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
            request.setCustomerId(authorizationGuard.currentCustomerId());
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

    @GetMapping("/events/{eventId}/tickets/seats")
    public ResponseEntity<?> getTicketSeats(@PathVariable String eventId) {
        try {
            TicketSeatMapResponse response = ticketingService.getSeats(
                    eventId,
                    authorizationGuard.currentCustomerId()
            );
            return ResponseEntity.ok(response);
        } catch (MarketplaceCheckoutException e) {
            return marketplaceError(e);
        }
    }

    @PostMapping("/events/{eventId}/tickets/seats/{seatId}/hold")
    public ResponseEntity<?> holdTicketSeat(@PathVariable String eventId,
                                            @PathVariable String seatId) {
        try {
            TicketSeatHoldResponse response = ticketingService.hold(
                    eventId,
                    seatId,
                    authorizationGuard.currentCustomerId()
            );
            return ResponseEntity.ok(response);
        } catch (MarketplaceCheckoutException e) {
            return marketplaceError(e);
        }
    }

    @DeleteMapping("/events/{eventId}/tickets/seats/{seatId}/hold")
    public ResponseEntity<?> releaseTicketSeat(@PathVariable String eventId,
                                               @PathVariable String seatId) {
        try {
            ticketingService.release(eventId, seatId, authorizationGuard.currentCustomerId());
            return ResponseEntity.noContent().build();
        } catch (MarketplaceCheckoutException e) {
            return marketplaceError(e);
        }
    }

    @GetMapping("/events/page")
    public ResponseEntity<Page<MarketplaceEventResponse>> getEventsPage(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String saleType,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "startsAt") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "24") int size) {
        return ResponseEntity.ok(marketplaceQueryService.getEventsPage(
                status, saleType, keyword, sort, page, size));
    }

    @GetMapping("/events/{eventId}/raffle/stream")
    public SseEmitter streamRaffleStatus(@PathVariable String eventId) {
        return raffleService.streamStatus(eventId);
    }

    @PostMapping("/events/{eventId}/raffle/draw")
    public ResponseEntity<?> drawRaffle(
            @PathVariable String eventId,
            @Valid @RequestBody RaffleDrawRequest request) {
        try {
            authorizationGuard.requireAdmin();
            securityAuditService.recordGranted("RAFFLE_DRAW_REQUESTED", "SALE_EVENT", eventId);
            return ResponseEntity.ok(raffleService.draw(eventId, request));
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
            request.setCustomerId(authorizationGuard.currentCustomerId());
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

    @GetMapping("/events/{eventId}/auction/me")
    public ResponseEntity<?> getMyAuctionStatus(@PathVariable String eventId) {
        try {
            return ResponseEntity.ok(auctionService.statusForCustomer(
                    eventId, authorizationGuard.currentCustomerId()));
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
            authorizationGuard.requireAdmin();
            securityAuditService.recordGranted("AUCTION_CLOSE_REQUESTED", "SALE_EVENT", eventId);
            return ResponseEntity.ok(auctionService.close(eventId));
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
            request.setCustomerId(authorizationGuard.currentCustomerId());
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

}

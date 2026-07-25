package com.example.payment.application.service;

import com.example.payment.domain.entity.TossPaymentIntent;
import com.example.payment.domain.model.inventory.Inventory;
import com.example.payment.domain.model.marketplace.AuctionSettlement;
import com.example.payment.domain.model.marketplace.AuctionSettlementStatus;
import com.example.payment.domain.model.marketplace.ListingStatus;
import com.example.payment.domain.model.marketplace.MarketplaceCheckoutType;
import com.example.payment.domain.model.marketplace.MarketplaceListing;
import com.example.payment.domain.model.marketplace.RaffleCheckoutStatus;
import com.example.payment.domain.model.marketplace.RaffleWinner;
import com.example.payment.domain.model.marketplace.SaleEvent;
import com.example.payment.domain.model.marketplace.SaleEventStatus;
import com.example.payment.domain.model.marketplace.SaleType;
import com.example.payment.domain.repository.AuctionSettlementRepository;
import com.example.payment.domain.repository.InventoryRepository;
import com.example.payment.domain.repository.MarketplaceListingRepository;
import com.example.payment.domain.repository.RaffleWinnerRepository;
import com.example.payment.domain.repository.SaleEventRepository;
import com.example.payment.domain.repository.TossPaymentIntentRepository;
import com.example.payment.infrastructure.gateway.TossPaymentsProperties;
import com.example.payment.infrastructure.util.IdGenerator;
import com.example.payment.presentation.dto.request.CompleteReservationRequest;
import com.example.payment.presentation.dto.request.MarketplaceCheckoutRequest;
import com.example.payment.presentation.dto.request.TossPaymentConfirmRequest;
import com.example.payment.presentation.dto.response.CompleteReservationResponse;
import com.example.payment.presentation.dto.response.TossPaymentIntentResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TossPaymentIntentService {

    private static final int INTENT_TTL_MINUTES = 10;
    private static final Set<SaleType> DIRECT_CHECKOUT_TYPES = Set.of(SaleType.FIXED_PRICE, SaleType.DROP);
    private static final Set<String> ACTIVE_INTENT_STATUSES = Set.of("READY", "AUTHENTICATED", "PENDING", "UNKNOWN");
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class
    );

    private final TossPaymentIntentRepository repository;
    private final CheckoutPricingService checkoutPricingService;
    private final CompleteReservationGateway completeReservationGateway;
    private final SaleEventRepository saleEventRepository;
    private final MarketplaceListingRepository marketplaceListingRepository;
    private final InventoryRepository inventoryRepository;
    private final RaffleWinnerRepository raffleWinnerRepository;
    private final AuctionSettlementRepository auctionSettlementRepository;
    private final MarketplaceOrderService marketplaceOrderService;
    private final ShippingAddressService shippingAddressService;
    private final TicketingService ticketingService;
    private final TossPaymentsProperties tossProperties;
    private final ObjectMapper objectMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    @Transactional
    public TossPaymentIntentResponse createIntent(CompleteReservationRequest request) {
        checkoutPricingService.applyProductPrice(request, true);
        request.setShippingInfo(shippingAddressService.resolveShippingInfo(request.getCustomerId(), request.getShippingInfo()));
        String requestHash = requestHash(request);

        TossPaymentIntent existing = repository.findByIdempotencyKey(request.getIdempotencyKey()).orElse(null);
        if (existing != null) {
            if (!existing.getRequestHash().equals(requestHash)) {
                throw new IdempotencyConflictException("IDEMPOTENCY_KEY_CONFLICT");
            }
            return toResponse(existing);
        }

        String intentId = "TOSS-INTENT-" + IdGenerator.generateEventId();
        TossPaymentIntent intent = TossPaymentIntent.builder()
                .intentId(intentId)
                .orderId(IdGenerator.generateOrderId())
                .idempotencyKey(request.getIdempotencyKey())
                .requestHash(requestHash)
                .customerId(request.getCustomerId())
                .productId(request.getProductId())
                .quantity(request.getQuantity())
                .amount(request.getPaymentInfo().getAmount())
                .currency(request.getPaymentInfo().getCurrency())
                .paymentMethod(defaultText(request.getPaymentInfo().getPaymentMethod(), "CREDIT_CARD"))
                .orderName(orderName(request))
                .customerKey(customerKey(request.getCustomerId()))
                .merchantId(request.getPaymentInfo().getMerchantId())
                .clientId(request.getClientId())
                .seatId(request.getSeatId())
                .status("READY")
                .successUrl(successUrl(request, intentId))
                .failUrl(failUrl(request, intentId))
                .expiresAt(LocalDateTime.now().plusMinutes(INTENT_TTL_MINUTES))
                .createdAt(LocalDateTime.now())
                .build();
        applyShippingSnapshot(intent, request.getShippingInfo());
        try {
            return toResponse(repository.save(intent));
        } catch (DataIntegrityViolationException race) {
            TossPaymentIntent raced = repository.findByIdempotencyKey(request.getIdempotencyKey())
                    .orElseThrow(() -> race);
            if (!raced.getRequestHash().equals(requestHash)) {
                throw new IdempotencyConflictException("IDEMPOTENCY_KEY_CONFLICT");
            }
            return toResponse(raced);
        }
    }

    @Transactional
    public TossPaymentIntentResponse createMarketplaceIntent(String eventId,
                                                             MarketplaceCheckoutType checkoutType,
                                                             MarketplaceCheckoutRequest request) {
        SaleEvent event = saleEventRepository.findByIdForUpdate(eventId)
                .orElseThrow(() -> new MarketplaceCheckoutException(HttpStatus.NOT_FOUND, "Sale event not found: " + eventId));
        MarketplaceListing listing = marketplaceListingRepository.findById(event.getListingId())
                .orElseThrow(() -> new MarketplaceCheckoutException(HttpStatus.NOT_FOUND, "Listing not found: " + event.getListingId()));
        request.setShippingInfo(ticketingService.isDigitalTicket(listing)
                ? null
                : shippingAddressService.resolveShippingInfo(request.getCustomerId(), request.getShippingInfo()));

        TossPaymentIntent existing = repository.findByIdempotencyKey(request.getIdempotencyKey()).orElse(null);
        MarketplaceIntentPrice price = marketplaceIntentPrice(event, listing, checkoutType, request, existing == null);
        CompleteReservationRequest completeRequest = toCompleteReservationRequest(event, listing, request, price);
        String requestHash = requestHash(completeRequest, Map.of(
                "saleEventId", event.getSaleEventId(),
                "listingId", listing.getListingId(),
                "marketplaceCheckoutType", checkoutType.name(),
                "marketplaceSourceId", price.sourceId()
        ));

        if (existing != null) {
            if (!existing.getRequestHash().equals(requestHash)) {
                throw new IdempotencyConflictException("IDEMPOTENCY_KEY_CONFLICT");
            }
            return toResponse(existing);
        }

        if (checkoutType == MarketplaceCheckoutType.DIRECT) {
            ticketingService.validateAndExtendCheckoutHold(
                    event,
                    listing,
                    request.getCustomerId(),
                    request.getSeatId(),
                    request.getQuantity()
            );
        }

        String intentId = "TOSS-INTENT-" + IdGenerator.generateEventId();
        TossPaymentIntent intent = TossPaymentIntent.builder()
                .intentId(intentId)
                .orderId(IdGenerator.generateOrderId())
                .idempotencyKey(request.getIdempotencyKey())
                .requestHash(requestHash)
                .customerId(request.getCustomerId())
                .productId(event.getProductId())
                .quantity(request.getQuantity())
                .amount(completeRequest.getPaymentInfo().getAmount())
                .currency(completeRequest.getPaymentInfo().getCurrency())
                .paymentMethod(defaultText(request.getPaymentInfo().getPaymentMethod(), "CREDIT_CARD"))
                .orderName(completeRequest.getPaymentInfo().getOrderName())
                .customerKey(customerKey(request.getCustomerId()))
                .merchantId(event.getSellerId())
                .clientId(completeRequest.getClientId())
                .seatId(request.getSeatId())
                .saleEventId(event.getSaleEventId())
                .listingId(listing.getListingId())
                .marketplaceCheckoutType(checkoutType.name())
                .marketplaceSourceId(price.sourceId())
                .status("READY")
                .successUrl(successUrl(completeRequest, intentId))
                .failUrl(failUrl(completeRequest, intentId))
                .expiresAt(LocalDateTime.now().plusMinutes(INTENT_TTL_MINUTES))
                .createdAt(LocalDateTime.now())
                .build();
        applyShippingSnapshot(intent, completeRequest.getShippingInfo());
        try {
            return toResponse(repository.save(intent));
        } catch (DataIntegrityViolationException race) {
            TossPaymentIntent raced = repository.findByIdempotencyKey(request.getIdempotencyKey())
                    .orElseThrow(() -> race);
            if (!raced.getRequestHash().equals(requestHash)) {
                throw new IdempotencyConflictException("IDEMPOTENCY_KEY_CONFLICT");
            }
            return toResponse(raced);
        }
    }

    public CompleteReservationResponse confirm(TossPaymentConfirmRequest request) {
        TossPaymentIntent intent = repository.findById(request.getIntentId())
                .orElseThrow(() -> new IllegalArgumentException("TOSS_PAYMENT_INTENT_NOT_FOUND"));

        validateConfirm(request, intent);

        boolean isLockRequired = "RAFFLE_WINNER".equals(intent.getMarketplaceCheckoutType())
                || "AUCTION_WINNER".equals(intent.getMarketplaceCheckoutType());
        String lockKey = isLockRequired ? "lock:checkout:" + intent.getMarketplaceSourceId() : null;
        String lockOwner = isLockRequired ? UUID.randomUUID().toString() : null;

        if (isLockRequired) {
            boolean acquired = false;
            for (int i = 0; i < 5; i++) {
                Boolean success = redisTemplate.opsForValue().setIfAbsent(lockKey, lockOwner, Duration.ofSeconds(30));
                if (Boolean.TRUE.equals(success)) {
                    acquired = true;
                    break;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "결제 처리 중 인터럽트가 발생했습니다.");
                }
            }
            if (!acquired) {
                throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "현재 다른 결제 요청이 처리 중입니다. 잠시 후 다시 시도해 주세요.");
            }
        }

        try {
            if (isLockRequired) {
                if ("RAFFLE_WINNER".equals(intent.getMarketplaceCheckoutType())) {
                    RaffleWinner winner = raffleWinnerRepository.findById(intent.getMarketplaceSourceId())
                            .orElseThrow(() -> new MarketplaceCheckoutException(HttpStatus.NOT_FOUND, "Raffle winner not found."));
                    if (winner.getCheckoutStatus() != RaffleCheckoutStatus.PENDING) {
                        throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "이미 결제 완료되었거나 만료된 당첨권입니다.");
                    }
                } else if ("AUCTION_WINNER".equals(intent.getMarketplaceCheckoutType())) {
                    AuctionSettlement settlement = auctionSettlementRepository.findById(intent.getMarketplaceSourceId())
                            .orElseThrow(() -> new MarketplaceCheckoutException(HttpStatus.NOT_FOUND, "Auction settlement not found."));
                    if (settlement.getStatus() != AuctionSettlementStatus.AWAITING_PAYMENT) {
                        throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "이미 결제 완료되었거나 취소된 낙찰건입니다.");
                    }
                }
            }

            if (intent.getResponseBody() != null && !intent.getResponseBody().isBlank()) {
                return readResponse(intent.getResponseBody());
            }

            if (hasText(intent.getWorkflowId())) {
                CompleteReservationResponse response = refreshWorkflowStatus(intent);
                if (response != null) {
                    persistTerminalResponse(intent, response);
                    recordMarketplaceCheckout(intent, response);
                    repository.save(intent);
                    return response;
                }
            }

            intent.setPaymentKey(request.getPaymentKey());
            intent.setStatus("AUTHENTICATED");
            repository.save(intent);

            CompleteReservationRequest completeRequest = toCompleteReservationRequest(intent);
            CompleteReservationResponse response = completeReservationGateway.processCompleteReservation(completeRequest);

            intent.setWorkflowId(response.getWorkflowId());
            intent.setStatus(response.getStatus());
            persistTerminalResponse(intent, response);
            recordMarketplaceCheckout(intent, response);
            repository.save(intent);
            return response;
        } finally {
            if (isLockRequired) {
                redisTemplate.execute(RELEASE_LOCK_SCRIPT, List.of(lockKey), lockOwner);
            }
        }
    }

    @Transactional
    public TossPaymentIntentResponse cancelReadyIntent(String intentId) {
        TossPaymentIntent intent = repository.findById(intentId)
                .orElseThrow(() -> new IllegalArgumentException("TOSS_PAYMENT_INTENT_NOT_FOUND"));
        if ("CANCELLED".equals(intent.getStatus()) || "CANCELED".equals(intent.getStatus())) {
            return toResponse(intent);
        }
        if (!"READY".equals(intent.getStatus())) {
            throw new IllegalArgumentException("TOSS_PAYMENT_INTENT_CANNOT_BE_CANCELLED: " + intent.getStatus());
        }
        intent.setStatus("CANCELLED");
        intent.setResponseBody(null);
        TossPaymentIntent saved = repository.save(intent);
        ticketingService.completeCheckout(intent.getSaleEventId(), intent.getSeatId(), intent.getCustomerId());
        return toResponse(saved);
    }

    public int reconcileRecoverableIntents(LocalDateTime cutoff) {
        List<TossPaymentIntent> intents = repository.findRecoverableIntents(
                Set.of("AUTHENTICATED", "PENDING", "UNKNOWN"),
                cutoff
        );
        int recovered = 0;
        for (TossPaymentIntent intent : intents) {
            CompleteReservationResponse response = recoverIntent(intent);
            if (response != null) {
                recovered++;
            }
        }
        return recovered;
    }

    public CompleteReservationResponse recoverIntentByProviderReference(String paymentKey, String orderId) {
        TossPaymentIntent intent = findIntentByProviderReference(paymentKey, orderId);
        if (hasText(paymentKey) && !paymentKey.equals(intent.getPaymentKey())) {
            intent.setPaymentKey(paymentKey);
            intent.setStatus("AUTHENTICATED");
            repository.save(intent);
        }

        if (intent.getResponseBody() != null && !intent.getResponseBody().isBlank()) {
            return readResponse(intent.getResponseBody());
        }

        CompleteReservationResponse response = recoverIntent(intent);
        if (response != null) {
            return response;
        }
        return CompleteReservationResponse.builder()
                .workflowId(intent.getWorkflowId())
                .status(defaultText(intent.getStatus(), "PENDING"))
                .message("Toss payment intent recovery is pending.")
                .timestamp(LocalDateTime.now())
                .build();
    }

    @Transactional
    public void markProviderTerminalStatus(String paymentKey, String orderId, String providerStatus) {
        TossPaymentIntent intent = findIntentByProviderReference(paymentKey, orderId);
        if (hasText(paymentKey) && !paymentKey.equals(intent.getPaymentKey())) {
            intent.setPaymentKey(paymentKey);
        }
        intent.setStatus(mapProviderStatus(providerStatus));
        repository.save(intent);
    }

    private CompleteReservationResponse recoverIntent(TossPaymentIntent intent) {
        CompleteReservationResponse response = null;
        if (hasText(intent.getWorkflowId())) {
            response = refreshWorkflowStatus(intent);
        } else if (hasText(intent.getPaymentKey())) {
            response = completeReservationGateway.processCompleteReservation(toCompleteReservationRequest(intent));
            intent.setWorkflowId(response.getWorkflowId());
            intent.setStatus(response.getStatus());
        }
        if (response == null) {
            return null;
        }
        persistTerminalResponse(intent, response);
        recordMarketplaceCheckout(intent, response);
        repository.save(intent);
        return response;
    }

    private TossPaymentIntent findIntentByProviderReference(String paymentKey, String orderId) {
        if (hasText(paymentKey)) {
            java.util.Optional<TossPaymentIntent> byPaymentKey = repository.findByPaymentKey(paymentKey);
            if (byPaymentKey.isPresent()) {
                return byPaymentKey.get();
            }
        }
        if (hasText(orderId)) {
            return repository.findByOrderId(orderId)
                    .orElseThrow(() -> new IllegalArgumentException("TOSS_PAYMENT_INTENT_NOT_FOUND"));
        }
        throw new IllegalArgumentException("TOSS_PAYMENT_REFERENCE_REQUIRED");
    }

    private String mapProviderStatus(String providerStatus) {
        if ("DONE".equals(providerStatus)) {
            return "SUCCESS";
        }
        if ("CANCELED".equals(providerStatus) || "PARTIAL_CANCELED".equals(providerStatus)) {
            return "CANCELLED";
        }
        if ("ABORTED".equals(providerStatus) || "EXPIRED".equals(providerStatus)) {
            return "FAILED";
        }
        return "UNKNOWN";
    }

    private void validateConfirm(TossPaymentConfirmRequest request, TossPaymentIntent intent) {
        if (!intent.getOrderId().equals(request.getOrderId())) {
            throw new IllegalArgumentException("TOSS_PAYMENT_CONFLICT");
        }
        if (intent.getPaymentKey() != null && !intent.getPaymentKey().equals(request.getPaymentKey())) {
            throw new IllegalArgumentException("TOSS_PAYMENT_CONFLICT");
        }
        if (LocalDateTime.now().isAfter(intent.getExpiresAt())) {
            throw new IllegalArgumentException("TOSS_PAYMENT_INTENT_EXPIRED");
        }
        if (money(intent.getAmount()).compareTo(money(request.getAmount())) != 0) {
            throw new AmountMismatchException("AMOUNT_MISMATCH: expected " + intent.getAmount() + " but Toss returned " + request.getAmount());
        }
    }

    private CompleteReservationRequest toCompleteReservationRequest(TossPaymentIntent intent) {
        return CompleteReservationRequest.builder()
                .productId(intent.getProductId())
                .customerId(intent.getCustomerId())
                .quantity(intent.getQuantity())
                .clientId(intent.getClientId())
                .seatId(intent.getSeatId())
                .idempotencyKey(intent.getIdempotencyKey())
                .correlationId("TOSS-" + intent.getIntentId())
                .shippingInfo(shippingInfo(intent))
                .paymentInfo(CompleteReservationRequest.PaymentInfo.builder()
                        .amount(intent.getAmount())
                        .currency(intent.getCurrency())
                        .paymentMethod(intent.getPaymentMethod())
                        .merchantId(intent.getMerchantId())
                        .orderName(intent.getOrderName())
                        .tossPaymentKey(intent.getPaymentKey())
                        .tossOrderId(intent.getOrderId())
                        .tossIntentId(intent.getIntentId())
                        .build())
                .build();
    }

    private CompleteReservationRequest toCompleteReservationRequest(SaleEvent event,
                                                                    MarketplaceListing listing,
                                                                    MarketplaceCheckoutRequest request,
                                                                    MarketplaceIntentPrice price) {
        return CompleteReservationRequest.builder()
                .productId(event.getProductId())
                .customerId(request.getCustomerId())
                .quantity(request.getQuantity())
                .clientId(defaultText(request.getClientId(), price.clientId()))
                .seatId(request.getSeatId())
                .idempotencyKey(request.getIdempotencyKey())
                .correlationId(request.getCorrelationId())
                .shippingInfo(request.getShippingInfo())
                .paymentInfo(CompleteReservationRequest.PaymentInfo.builder()
                        .amount(price.amount())
                        .currency("KRW")
                        .paymentMethod(request.getPaymentInfo().getPaymentMethod())
                        .merchantId(event.getSellerId())
                        .orderName(price.orderName(listing))
                        .successUrl(request.getPaymentInfo().getSuccessUrl())
                        .failUrl(request.getPaymentInfo().getFailUrl())
                        .cancelUrl(request.getPaymentInfo().getCancelUrl())
                        .build())
                .build();
    }

    private MarketplaceCheckoutRequest toMarketplaceCheckoutRequest(TossPaymentIntent intent) {
        MarketplaceCheckoutRequest request = new MarketplaceCheckoutRequest();
        request.setCustomerId(intent.getCustomerId());
        request.setQuantity(intent.getQuantity());
        request.setIdempotencyKey(intent.getIdempotencyKey());
        request.setClientId(intent.getClientId());
        request.setSeatId(intent.getSeatId());
        request.setPaymentInfo(CompleteReservationRequest.PaymentInfo.builder()
                .amount(intent.getAmount())
                .currency(intent.getCurrency())
                .paymentMethod(intent.getPaymentMethod())
                .merchantId(intent.getMerchantId())
                .orderName(intent.getOrderName())
                .successUrl(intent.getSuccessUrl())
                .failUrl(intent.getFailUrl())
                .tossPaymentKey(intent.getPaymentKey())
                .tossOrderId(intent.getOrderId())
                .tossIntentId(intent.getIntentId())
                .build());
        request.setShippingInfo(shippingInfo(intent));
        return request;
    }

    private MarketplaceIntentPrice marketplaceIntentPrice(SaleEvent event,
                                                          MarketplaceListing listing,
                                                          MarketplaceCheckoutType checkoutType,
                                                          MarketplaceCheckoutRequest request,
                                                          boolean enforceNewIntentCapacity) {
        requireActiveListing(listing);
        if (checkoutType == MarketplaceCheckoutType.DIRECT) {
            validateDirectCheckout(event, request.getQuantity(), enforceNewIntentCapacity);
            BigDecimal amount = money(event.getPrice()).multiply(BigDecimal.valueOf(request.getQuantity()));
            requireClientAmountMatches(request, amount);
            return new MarketplaceIntentPrice(amount, event.getSaleEventId(), "marketplace", listing.getTitle());
        }
        if (checkoutType == MarketplaceCheckoutType.RAFFLE_WINNER) {
            RaffleWinner winner = validateRaffleWinnerCheckout(event, request.getCustomerId(), enforceNewIntentCapacity);
            BigDecimal amount = money(event.getPrice()).multiply(BigDecimal.valueOf(request.getQuantity()));
            requireClientAmountMatches(request, amount);
            return new MarketplaceIntentPrice(amount, winner.getWinnerId(), "marketplace-raffle", "Raffle winner checkout");
        }
        if (checkoutType == MarketplaceCheckoutType.AUCTION_WINNER) {
            AuctionSettlement settlement = auctionSettlementRepository
                    .findBySaleEventIdAndCustomerIdAndStatus(
                            event.getSaleEventId(),
                            request.getCustomerId(),
                            AuctionSettlementStatus.AWAITING_PAYMENT
                    )
                    .orElseThrow(() -> new MarketplaceCheckoutException(HttpStatus.FORBIDDEN, "Only the winning auction bidder can checkout."));
            if (request.getQuantity() != 1) {
                throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "Auction winner checkout quantity must be 1.");
            }
            validateAuctionSettlementPaymentWindow(settlement);
            if (enforceNewIntentCapacity) {
                ensureNoActiveCheckoutIntent(settlement.getSettlementId());
            }
            requireAvailableInventory(event, 1, false);
            requireClientAmountMatches(request, settlement.getAmount());
            return new MarketplaceIntentPrice(money(settlement.getAmount()), settlement.getSettlementId(), "marketplace-auction", "Auction winner checkout");
        }
        throw new MarketplaceCheckoutException(HttpStatus.BAD_REQUEST, "Unsupported marketplace checkout type: " + checkoutType);
    }

    private void validateDirectCheckout(SaleEvent event, Integer quantity, boolean enforceNewIntentCapacity) {
        if (event.getStatus() != SaleEventStatus.LIVE) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "Sale event is not live.");
        }
        if (!DIRECT_CHECKOUT_TYPES.contains(event.getSaleType())) {
            throw new MarketplaceCheckoutException(
                    HttpStatus.CONFLICT,
                    "This sale type requires a dedicated winner or bid checkout flow: " + event.getSaleType()
            );
        }
        validateEventWindow(event);
        requireAvailableInventory(event, quantity, enforceNewIntentCapacity);
    }

    private RaffleWinner validateRaffleWinnerCheckout(SaleEvent event, String customerId, boolean enforceNewIntentCapacity) {
        if (event.getSaleType() != SaleType.RAFFLE) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "Sale event is not a raffle.");
        }
        RaffleWinner winner = raffleWinnerRepository.findBySaleEventIdAndCustomerId(event.getSaleEventId(), customerId)
                .orElseThrow(() -> new MarketplaceCheckoutException(HttpStatus.FORBIDDEN, "Only selected raffle winners can checkout."));
        if (winner.getCheckoutStatus() != RaffleCheckoutStatus.PENDING) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "Raffle winner checkout is already processed.");
        }
        validateRaffleWinnerPaymentWindow(winner);
        if (enforceNewIntentCapacity) {
            ensureNoActiveCheckoutIntent(winner.getWinnerId());
        }
        requireAvailableInventory(event, 1, false);
        return winner;
    }

    private void validateEventWindow(SaleEvent event) {
        LocalDateTime now = LocalDateTime.now();
        if (event.getStartsAt() != null && event.getStartsAt().isAfter(now)) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "Sale event has not started.");
        }
        if (event.getEndsAt() != null && !event.getEndsAt().isAfter(now)) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "Sale event has ended.");
        }
    }

    private void requireActiveListing(MarketplaceListing listing) {
        if (listing.getStatus() != ListingStatus.ACTIVE) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "Listing is not active.");
        }
    }

    private void requireAvailableInventory(SaleEvent event, Integer quantity, boolean includeActiveIntents) {
        if (quantity == null || quantity <= 0) {
            throw new MarketplaceCheckoutException(HttpStatus.BAD_REQUEST, "quantity must be greater than 0.");
        }
        Inventory inventory = inventoryRepository.findByIdForUpdate(event.getProductId())
                .orElseThrow(() -> new MarketplaceCheckoutException(HttpStatus.NOT_FOUND, "Inventory not found for product: " + event.getProductId()));
        long activeIntentQuantity = includeActiveIntents
                ? repository.sumActiveMarketplaceQuantity(
                        event.getSaleEventId(),
                        MarketplaceCheckoutType.DIRECT.name(),
                        ACTIVE_INTENT_STATUSES,
                        LocalDateTime.now()
                )
                : 0L;
        int eventStockLimit = event.getStockQuantity() != null
                ? Math.min(event.getStockQuantity(), inventory.getAvailableQuantity())
                : inventory.getAvailableQuantity();
        long remainingForNewIntent = eventStockLimit - activeIntentQuantity;
        if (remainingForNewIntent < quantity) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "SOLD_OUT");
        }
    }

    private void ensureNoActiveCheckoutIntent(String sourceId) {
        long activeCount = repository.countActiveMarketplaceSource(
                sourceId,
                ACTIVE_INTENT_STATUSES,
                LocalDateTime.now()
        );
        if (activeCount > 0) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "CHECKOUT_ALREADY_IN_PROGRESS");
        }
    }

    private void validateRaffleWinnerPaymentWindow(RaffleWinner winner) {
        if (winner.getCheckoutExpiresAt() != null && !winner.getCheckoutExpiresAt().isAfter(LocalDateTime.now())) {
            if (winner.getCheckoutStatus() == RaffleCheckoutStatus.PENDING) {
                winner.setCheckoutStatus(RaffleCheckoutStatus.EXPIRED);
                raffleWinnerRepository.save(winner);
            }
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "RAFFLE_WINNER_CHECKOUT_EXPIRED");
        }
    }

    private void validateAuctionSettlementPaymentWindow(AuctionSettlement settlement) {
        if (settlement.getCheckoutExpiresAt() != null && !settlement.getCheckoutExpiresAt().isAfter(LocalDateTime.now())) {
            if (settlement.getStatus() == AuctionSettlementStatus.AWAITING_PAYMENT) {
                settlement.setStatus(AuctionSettlementStatus.CANCELLED);
                auctionSettlementRepository.save(settlement);
            }
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "AUCTION_WINNER_CHECKOUT_EXPIRED");
        }
    }

    private void requireClientAmountMatches(MarketplaceCheckoutRequest request, BigDecimal expectedAmount) {
        BigDecimal clientAmount = request.getPaymentInfo().getAmount();
        if (clientAmount != null && money(clientAmount).compareTo(money(expectedAmount)) != 0) {
            throw new AmountMismatchException(
                    "AMOUNT_MISMATCH: expected " + money(expectedAmount) + " KRW but client sent " + clientAmount
            );
        }
    }

    private void recordMarketplaceCheckout(TossPaymentIntent intent, CompleteReservationResponse response) {
        if (intent.getMarketplaceCheckoutType() == null || intent.getMarketplaceCheckoutType().isBlank()) {
            return;
        }
        if (!"SUCCESS".equals(response.getStatus())) {
            return;
        }

        SaleEvent event = saleEventRepository.findById(intent.getSaleEventId())
                .orElseThrow(() -> new MarketplaceCheckoutException(HttpStatus.NOT_FOUND, "Sale event not found: " + intent.getSaleEventId()));
        MarketplaceListing listing = marketplaceListingRepository.findById(intent.getListingId())
                .orElseThrow(() -> new MarketplaceCheckoutException(HttpStatus.NOT_FOUND, "Listing not found: " + intent.getListingId()));
        MarketplaceCheckoutType checkoutType = MarketplaceCheckoutType.valueOf(intent.getMarketplaceCheckoutType());

        marketplaceOrderService.recordCheckout(
                event,
                listing,
                toMarketplaceCheckoutRequest(intent),
                response,
                checkoutType,
                intent.getMarketplaceSourceId(),
                intent.getAmount()
        );
        ticketingService.completeCheckout(intent.getSaleEventId(), intent.getSeatId(), intent.getCustomerId());

        if (checkoutType == MarketplaceCheckoutType.RAFFLE_WINNER) {
            raffleWinnerRepository.findById(intent.getMarketplaceSourceId()).ifPresent(winner -> {
                winner.setCheckoutStatus(RaffleCheckoutStatus.COMPLETED);
                winner.setCheckoutCompletedAt(LocalDateTime.now());
                raffleWinnerRepository.save(winner);
            });
        }
        if (checkoutType == MarketplaceCheckoutType.AUCTION_WINNER) {
            auctionSettlementRepository.findById(intent.getMarketplaceSourceId()).ifPresent(settlement -> {
                settlement.setStatus(AuctionSettlementStatus.PAID);
                settlement.setPaidAt(LocalDateTime.now());
                auctionSettlementRepository.save(settlement);
            });
        }
    }

    private TossPaymentIntentResponse toResponse(TossPaymentIntent intent) {
        return TossPaymentIntentResponse.builder()
                .intentId(intent.getIntentId())
                .orderId(intent.getOrderId())
                .orderName(intent.getOrderName())
                .amount(intent.getAmount())
                .currency(intent.getCurrency())
                .customerKey(intent.getCustomerKey())
                .clientKey(tossProperties.getClientKey())
                .successUrl(intent.getSuccessUrl())
                .failUrl(intent.getFailUrl())
                .status(intent.getStatus())
                .expiresAt(intent.getExpiresAt())
                .build();
    }

    private void applyShippingSnapshot(TossPaymentIntent intent, CompleteReservationRequest.ShippingInfo shippingInfo) {
        if (shippingInfo == null) {
            return;
        }
        intent.setShippingAddressId(shippingInfo.getAddressId());
        intent.setShippingRecipientName(shippingInfo.getRecipientName());
        intent.setShippingContactPhone(shippingInfo.getContactPhone());
        intent.setShippingPostalCode(shippingInfo.getPostalCode());
        intent.setShippingAddress(shippingInfo.getAddress());
        intent.setShippingMethod(shippingInfo.getMethod());
        intent.setShippingMemo(shippingInfo.getSpecialInstructions());
    }

    private CompleteReservationRequest.ShippingInfo shippingInfo(TossPaymentIntent intent) {
        if (!hasText(intent.getShippingAddressId())
                && !hasText(intent.getShippingAddress())
                && !hasText(intent.getShippingContactPhone())) {
            return null;
        }
        return CompleteReservationRequest.ShippingInfo.builder()
                .addressId(intent.getShippingAddressId())
                .recipientName(intent.getShippingRecipientName())
                .postalCode(intent.getShippingPostalCode())
                .address(intent.getShippingAddress())
                .method(intent.getShippingMethod())
                .specialInstructions(intent.getShippingMemo())
                .contactPhone(intent.getShippingContactPhone())
                .build();
    }

    private CompleteReservationResponse readResponse(String responseBody) {
        try {
            return objectMapper.readValue(responseBody, CompleteReservationResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("Stored Toss payment response is invalid", e);
        }
    }

    private CompleteReservationResponse refreshWorkflowStatus(TossPaymentIntent intent) {
        CompleteReservationResponse response = completeReservationGateway.getWorkflowStatus(intent.getWorkflowId());
        if (response == null) {
            return null;
        }
        intent.setStatus(response.getStatus());
        if (hasText(response.getWorkflowId())) {
            intent.setWorkflowId(response.getWorkflowId());
        }
        return response;
    }

    private void persistTerminalResponse(TossPaymentIntent intent, CompleteReservationResponse response) {
        if (!isTerminal(response)) {
            intent.setResponseBody(null);
            return;
        }
        try {
            intent.setResponseBody(objectMapper.writeValueAsString(response));
        } catch (Exception e) {
            intent.setResponseBody(null);
        }
    }

    private boolean isTerminal(CompleteReservationResponse response) {
        if (response == null || response.getStatus() == null) {
            return false;
        }
        return switch (response.getStatus()) {
            case "SUCCESS", "FAILED", "CANCELLED", "CANCELED" -> true;
            default -> false;
        };
    }

    private String requestHash(CompleteReservationRequest request) {
        return requestHash(request, Map.of());
    }

    private String requestHash(CompleteReservationRequest request, Map<String, Object> extra) {
        try {
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("productId", request.getProductId());
            canonical.put("customerId", request.getCustomerId());
            canonical.put("quantity", request.getQuantity());
            canonical.put("clientId", request.getClientId());
            canonical.put("seatId", request.getSeatId());
            canonical.put("amount", request.getPaymentInfo().getAmount());
            canonical.put("currency", request.getPaymentInfo().getCurrency());
            canonical.put("paymentMethod", request.getPaymentInfo().getPaymentMethod());
            canonical.put("merchantId", request.getPaymentInfo().getMerchantId());
            CompleteReservationRequest.ShippingInfo shippingInfo = request.getShippingInfo();
            canonical.put("shippingAddressId", shippingInfo != null ? shippingInfo.getAddressId() : null);
            canonical.put("shippingRecipientName", shippingInfo != null ? shippingInfo.getRecipientName() : null);
            canonical.put("shippingPostalCode", shippingInfo != null ? shippingInfo.getPostalCode() : null);
            canonical.put("shippingAddress", shippingInfo != null ? shippingInfo.getAddress() : null);
            canonical.put("shippingContactPhone", shippingInfo != null ? shippingInfo.getContactPhone() : null);
            canonical.putAll(extra);
            byte[] json = objectMapper.writeValueAsBytes(canonical);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash Toss payment intent request", e);
        }
    }

    private String orderName(CompleteReservationRequest request) {
        String requested = request.getPaymentInfo().getOrderName();
        if (requested != null && !requested.isBlank()) {
            return requested.length() > 100 ? requested.substring(0, 100) : requested;
        }
        String fallback = "EverySale " + request.getProductId() + " x " + request.getQuantity();
        return fallback.length() > 100 ? fallback.substring(0, 100) : fallback;
    }

    private String successUrl(CompleteReservationRequest request, String intentId) {
        String value = request.getPaymentInfo().getSuccessUrl();
        return withIntentId(defaultText(value, "/app/?tossResult=success"), intentId);
    }

    private String failUrl(CompleteReservationRequest request, String intentId) {
        String value = request.getPaymentInfo().getFailUrl();
        return withIntentId(defaultText(value, "/app/?tossResult=fail"), intentId);
    }

    private String withIntentId(String url, String intentId) {
        if (url.contains("intentId=")) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + "intentId=" + intentId;
    }

    private String customerKey(String customerId) {
        String normalized = defaultText(customerId, "anonymous").replaceAll("[^A-Za-z0-9_-]", "_");
        return normalized.length() > 50 ? normalized.substring(0, 50) : normalized;
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private record MarketplaceIntentPrice(BigDecimal amount, String sourceId, String clientId, String orderName) {
        String orderName(MarketplaceListing listing) {
            String value = orderName == null || orderName.isBlank() ? listing.getTitle() : orderName;
            return value.length() > 100 ? value.substring(0, 100) : value;
        }
    }
}

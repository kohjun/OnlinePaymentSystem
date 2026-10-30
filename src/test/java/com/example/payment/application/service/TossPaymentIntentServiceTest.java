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
import com.example.payment.presentation.dto.request.CompleteReservationRequest;
import com.example.payment.presentation.dto.request.MarketplaceCheckoutRequest;
import com.example.payment.presentation.dto.request.TossPaymentConfirmRequest;
import com.example.payment.presentation.dto.response.CompleteReservationResponse;
import com.example.payment.presentation.dto.response.TossPaymentIntentResponse;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import com.example.payment.infrastructure.security.CommerceActor;

import java.time.Duration;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class TossPaymentIntentServiceTest {

    private final TossPaymentIntentRepository repository = mock(TossPaymentIntentRepository.class);
    private final CheckoutPricingService checkoutPricingService = mock(CheckoutPricingService.class);
    private final CompleteReservationGateway completeReservationGateway = mock(CompleteReservationGateway.class);
    private final SaleEventRepository saleEventRepository = mock(SaleEventRepository.class);
    private final MarketplaceListingRepository listingRepository = mock(MarketplaceListingRepository.class);
    private final InventoryRepository inventoryRepository = mock(InventoryRepository.class);
    private final InventoryHoldService inventoryHoldService = mock(InventoryHoldService.class);
    private final CommercePolicyService commercePolicyService = mock(CommercePolicyService.class);
    private final RaffleWinnerRepository raffleWinnerRepository = mock(RaffleWinnerRepository.class);
    private final AuctionSettlementRepository auctionSettlementRepository = mock(AuctionSettlementRepository.class);
    private final MarketplaceOrderService marketplaceOrderService = mock(MarketplaceOrderService.class);
    private final ShippingAddressService shippingAddressService = mock(ShippingAddressService.class);
    private final TicketingService ticketingService = mock(TicketingService.class);
    private final TossPaymentsProperties tossProperties = new TossPaymentsProperties();
    private final TossIntentStateWriter stateWriter = mock(TossIntentStateWriter.class);
    private final RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
    private TossPaymentIntentService service;

    @BeforeEach
    void setUp() {
        tossProperties.setClientKey("test_ck_client");
        when(stateWriter.cancelReady(any())).thenAnswer(call -> {
            TossPaymentIntent intent = repository.findById(call.getArgument(0)).orElseThrow();
            intent.setStatus("CANCELLED");
            return intent;
        });
        when(stateWriter.authenticate(any())).thenAnswer(call -> {
            TossPaymentConfirmRequest request = call.getArgument(0);
            TossPaymentIntent intent = repository.findById(request.getIntentId()).orElseThrow();
            intent.setPaymentKey(request.getPaymentKey());
            intent.setStatus("AUTHENTICATED");
            return intent;
        });
        org.springframework.data.redis.core.ValueOperations valueOps = mock(org.springframework.data.redis.core.ValueOperations.class);
        org.springframework.data.redis.core.SetOperations setOps = mock(org.springframework.data.redis.core.SetOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(valueOps.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true);

        service = new TossPaymentIntentService(
                repository,
                checkoutPricingService,
                completeReservationGateway,
                saleEventRepository,
                listingRepository,
                inventoryRepository,
                inventoryHoldService,
                commercePolicyService,
                raffleWinnerRepository,
                auctionSettlementRepository,
                marketplaceOrderService,
                shippingAddressService,
                ticketingService,
                tossProperties,
                new ObjectMapper(),
                stateWriter,
                redisTemplate,
                new CheckoutRedirectPolicy("http://localhost:8080")
        );
    }

    @Test
    void createsMarketplaceDirectIntentWithSaleEventPriceAndMetadata() {
        stubDirectDrop();
        when(repository.findByIdempotencyKey("IDEMP-1")).thenReturn(Optional.empty());
        when(repository.save(any(TossPaymentIntent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TossPaymentIntentResponse response = service.createMarketplaceIntent(
                "EVT-DROP",
                MarketplaceCheckoutType.DIRECT,
                actor(),
                marketplaceRequest(new BigDecimal("150000"))
        );

        assertEquals(new BigDecimal("150000.00"), response.getAmount());
        assertEquals("test_ck_client", response.getClientKey());

        ArgumentCaptor<TossPaymentIntent> captor = ArgumentCaptor.forClass(TossPaymentIntent.class);
        verify(repository).save(captor.capture());
        TossPaymentIntent saved = captor.getValue();
        assertEquals("EVT-DROP", saved.getSaleEventId());
        assertEquals("LIST-DROP", saved.getListingId());
        assertEquals("DIRECT", saved.getMarketplaceCheckoutType());
        assertEquals("EVT-DROP", saved.getMarketplaceSourceId());
        assertNotNull(saved.getInventoryReservationId());
        assertEquals("SELLER-1", saved.getMerchantId());
        assertEquals("Limited Drop", saved.getOrderName());
        verify(inventoryHoldService).hold(
                eq(saved.getInventoryReservationId()),
                eq("PROD-DROP"),
                eq("CUST-1"),
                eq(1),
                any(),
                eq("DIRECT_INTENT"),
                eq("TOSS_INTENT"),
                eq(saved.getIntentId())
        );
        verify(inventoryHoldService).claim(
                saved.getInventoryReservationId(), saved.getIntentId(), "PROD-DROP", "CUST-1", 1);
    }

    @Test
    void cancellingReadyTicketIntentReleasesSeatHold() {
        TossPaymentIntent intent = TossPaymentIntent.builder()
                .intentId("INTENT-TICKET")
                .customerId("CUST-1")
                .saleEventId("EVT-TICKET")
                .seatId("EVT-TICKET-SEAT-0001")
                .status("READY")
                .build();
        when(repository.findById("INTENT-TICKET")).thenReturn(Optional.of(intent));
        when(repository.save(any(TossPaymentIntent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TossPaymentIntentResponse response = service.cancelReadyIntent("INTENT-TICKET");

        assertEquals("CANCELLED", response.getStatus());
        verify(ticketingService).completeCheckout(
                "EVT-TICKET",
                "EVT-TICKET-SEAT-0001",
                "CUST-1"
        );
    }

    @Test
    void cancellingReadyDirectIntentReturnsInventoryHold() {
        TossPaymentIntent intent = TossPaymentIntent.builder()
                .intentId("INTENT-DIRECT")
                .customerId("CUST-1")
                .status("READY")
                .inventoryReservationId("INTENT-HOLD-1")
                .build();
        when(repository.findById("INTENT-DIRECT")).thenReturn(Optional.of(intent));
        when(repository.save(any(TossPaymentIntent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.cancelReadyIntent("INTENT-DIRECT");

        verify(inventoryHoldService).release("INTENT-HOLD-1", "CANCELLED");
        assertEquals("CANCELLED", intent.getStatus());
    }

    @Test
    void rejectsMarketplaceIntentWhenClientAmountDoesNotMatchEventPrice() {
        stubDirectDrop();

        assertThrows(AmountMismatchException.class, () -> service.createMarketplaceIntent(
                "EVT-DROP",
                MarketplaceCheckoutType.DIRECT,
                actor(),
                marketplaceRequest(new BigDecimal("1"))
        ));

        verify(repository, never()).save(any());
    }

    @Test
    void rejectsDirectIntentWhenCustomerPurchaseLimitIsExhausted() {
        stubDirectDrop();
        doThrow(new MarketplaceCheckoutException(
                HttpStatus.CONFLICT, "PURCHASE_LIMIT_EXCEEDED", "limit exceeded"))
                .when(commercePolicyService)
                .requireDirectPurchaseCapacity(any(), any(), eq(1));

        MarketplaceCheckoutException ex = assertThrows(MarketplaceCheckoutException.class, () -> service.createMarketplaceIntent(
                "EVT-DROP",
                MarketplaceCheckoutType.DIRECT,
                actor(),
                marketplaceRequest(new BigDecimal("150000"))
        ));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("PURCHASE_LIMIT_EXCEEDED", ex.getCode());
        verify(repository, never()).save(any());
    }

    @Test
    void createsAuctionWinnerIntentWithSettlementAmountAndSourceId() {
        when(saleEventRepository.findByIdForUpdate("EVT-AUCTION")).thenReturn(Optional.of(auctionEvent()));
        when(listingRepository.findById("LIST-AUCTION")).thenReturn(Optional.of(auctionListing()));
        when(auctionSettlementRepository.findFirstBySaleEventIdAndCustomerIdAndStatusOrderByOfferSequenceDesc(
                "EVT-AUCTION",
                "CUST-1",
                AuctionSettlementStatus.AWAITING_PAYMENT
        )).thenReturn(Optional.of(AuctionSettlement.builder()
                .settlementId("SET-1")
                .saleEventId("EVT-AUCTION")
                .winningBidId("BID-1")
                .customerId("CUST-1")
                .sellerId("SELLER-1")
                .amount(new BigDecimal("9000000"))
                .status(AuctionSettlementStatus.AWAITING_PAYMENT)
                .inventoryReservationId("WINRES-AUCTION-1")
                .createdAt(LocalDateTime.now())
                .checkoutExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(30))
                .build()));
        when(repository.countActiveMarketplaceSource(eq("SET-1"), any(), any())).thenReturn(0L);
        when(inventoryRepository.findByIdForUpdate("PROD-AUCTION")).thenReturn(Optional.of(Inventory.builder()
                .productId("PROD-AUCTION")
                .totalQuantity(1)
                .availableQuantity(1)
                .reservedQuantity(0)
                .build()));
        when(repository.findByIdempotencyKey("IDEMP-1")).thenReturn(Optional.empty());
        when(repository.save(any(TossPaymentIntent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TossPaymentIntentResponse response = service.createMarketplaceIntent(
                "EVT-AUCTION",
                MarketplaceCheckoutType.AUCTION_WINNER,
                actor(),
                marketplaceRequest(new BigDecimal("9000000"))
        );

        assertEquals(new BigDecimal("9000000.00"), response.getAmount());

        ArgumentCaptor<TossPaymentIntent> captor = ArgumentCaptor.forClass(TossPaymentIntent.class);
        verify(repository).save(captor.capture());
        TossPaymentIntent saved = captor.getValue();
        assertEquals("AUCTION_WINNER", saved.getMarketplaceCheckoutType());
        assertEquals("SET-1", saved.getMarketplaceSourceId());
        assertEquals("WINRES-AUCTION-1", saved.getInventoryReservationId());
        assertEquals(new BigDecimal("9000000.00"), saved.getAmount());
        assertEquals("Auction winner checkout", saved.getOrderName());
        verify(inventoryHoldService).claim(
                "WINRES-AUCTION-1",
                saved.getIntentId(),
                "PROD-AUCTION",
                "CUST-1",
                1
        );
    }

    @Test
    void confirmMarketplaceRaffleIntentRecordsOrderAndCompletesWinner() {
        TossPaymentIntent intent = TossPaymentIntent.builder()
                .intentId("TOSS-INTENT-1")
                .orderId("ORD-TOSS-1")
                .idempotencyKey("IDEMP-1")
                .requestHash("HASH")
                .customerId("CUST-1")
                .productId("PROD-RAFFLE")
                .quantity(1)
                .amount(new BigDecimal("239000.00"))
                .currency("KRW")
                .paymentMethod("CREDIT_CARD")
                .orderName("Raffle winner checkout")
                .customerKey("CUST-1")
                .merchantId("SELLER-1")
                .clientId("marketplace-raffle")
                .saleEventId("EVT-RAFFLE")
                .listingId("LIST-RAFFLE")
                .marketplaceCheckoutType("RAFFLE_WINNER")
                .marketplaceSourceId("RFWIN-1")
                .status("READY")
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .createdAt(LocalDateTime.now())
                .build();
        RaffleWinner winner = RaffleWinner.builder()
                .winnerId("RFWIN-1")
                .saleEventId("EVT-RAFFLE")
                .entryId("ENTRY-1")
                .customerId("CUST-1")
                .checkoutStatus(RaffleCheckoutStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();

        when(repository.findById("TOSS-INTENT-1")).thenReturn(Optional.of(intent));
        when(repository.save(any(TossPaymentIntent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(saleEventRepository.findById("EVT-RAFFLE")).thenReturn(Optional.of(raffleEvent()));
        when(listingRepository.findById("LIST-RAFFLE")).thenReturn(Optional.of(raffleListing()));
        when(raffleWinnerRepository.findById("RFWIN-1")).thenReturn(Optional.of(winner));
        when(completeReservationGateway.processCompleteReservation(any()))
                .thenReturn(CompleteReservationResponse.success(
                        "RES-1", "ORD-TOSS-1", "PAY-1", "paykey_1",
                        "PROD-RAFFLE", 1, new BigDecimal("239000.00"), "KRW"
                ));

        CompleteReservationResponse response = service.confirm(TossPaymentConfirmRequest.builder()
                .intentId("TOSS-INTENT-1")
                .paymentKey("paykey_1")
                .orderId("ORD-TOSS-1")
                .amount(new BigDecimal("239000.00"))
                .build());

        assertEquals("SUCCESS", response.getStatus());
        verify(marketplaceOrderService).recordCheckout(
                eq(raffleEvent()),
                eq(raffleListing()),
                any(),
                eq(response),
                eq(MarketplaceCheckoutType.RAFFLE_WINNER),
                eq("RFWIN-1"),
                eq(new BigDecimal("239000.00"))
        );
        assertEquals(RaffleCheckoutStatus.COMPLETED, winner.getCheckoutStatus());
        verify(raffleWinnerRepository).save(winner);
    }

    @Test
    void confirmPendingDoesNotCacheResponseBody() {
        TossPaymentIntent intent = baseIntent();
        when(repository.findById("TOSS-INTENT-1")).thenReturn(Optional.of(intent));
        when(repository.save(any(TossPaymentIntent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(completeReservationGateway.processCompleteReservation(any()))
                .thenReturn(CompleteReservationResponse.builder()
                        .workflowId("WF-1")
                        .status("PENDING")
                        .message("workflow is still running")
                        .build());

        CompleteReservationResponse response = service.confirm(TossPaymentConfirmRequest.builder()
                .intentId("TOSS-INTENT-1")
                .paymentKey("paykey_1")
                .orderId("ORD-TOSS-1")
                .amount(new BigDecimal("10000.00"))
                .build());

        assertEquals("PENDING", response.getStatus());
        assertEquals("WF-1", intent.getWorkflowId());
        assertEquals("PENDING", intent.getStatus());
        assertNull(intent.getResponseBody());
    }

    @Test
    void duplicateConfirmWithPendingIntentRefreshesWorkflowStatus() {
        TossPaymentIntent intent = baseIntent();
        intent.setPaymentKey("paykey_1");
        intent.setWorkflowId("WF-1");
        intent.setStatus("PENDING");

        CompleteReservationResponse completed = CompleteReservationResponse.builder()
                .workflowId("WF-1")
                .status("SUCCESS")
                .message("completed")
                .build();

        when(repository.findById("TOSS-INTENT-1")).thenReturn(Optional.of(intent));
        when(repository.save(any(TossPaymentIntent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(completeReservationGateway.getWorkflowStatus("WF-1")).thenReturn(completed);

        CompleteReservationResponse response = service.confirm(TossPaymentConfirmRequest.builder()
                .intentId("TOSS-INTENT-1")
                .paymentKey("paykey_1")
                .orderId("ORD-TOSS-1")
                .amount(new BigDecimal("10000.00"))
                .build());

        assertEquals("SUCCESS", response.getStatus());
        assertNotNull(intent.getResponseBody());
        verify(completeReservationGateway, never()).processCompleteReservation(any());
    }

    @Test
    void rejectsGenericIntentForMarketplaceListingProduct() {
        when(listingRepository.existsByProductId("PROD-DROP")).thenReturn(true);

        MarketplaceCheckoutException rejected = assertThrows(MarketplaceCheckoutException.class,
                () -> service.createIntent(genericRequest("PROD-DROP")));

        assertEquals(HttpStatus.CONFLICT, rejected.getStatus());
        assertEquals("MARKETPLACE_CHECKOUT_REQUIRED", rejected.getCode());
        verify(checkoutPricingService, never()).applyProductPrice(any(), anyBoolean());
        verify(repository, never()).save(any(TossPaymentIntent.class));
    }

    @Test
    void createsGenericIntentForProductOutsideMarketplace() {
        when(listingRepository.existsByProductId("PROD-1")).thenReturn(false);
        when(repository.findByIdempotencyKey("IDEMP-GENERIC")).thenReturn(Optional.empty());
        when(repository.save(any(TossPaymentIntent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TossPaymentIntentResponse response = service.createIntent(genericRequest("PROD-1"));

        assertNotNull(response.getIntentId());
        verify(checkoutPricingService).applyProductPrice(any(), eq(true));
        verify(repository).save(any(TossPaymentIntent.class));
    }

    @Test
    void rejectsRedirectToForeignOriginBeforeHoldingInventory() {
        stubDirectDrop();
        when(repository.findByIdempotencyKey("IDEMP-1")).thenReturn(Optional.empty());
        MarketplaceCheckoutRequest request = marketplaceRequest(new BigDecimal("150000"));
        request.getPaymentInfo().setSuccessUrl("https://attacker.example/collect");

        MarketplaceCheckoutException rejected = assertThrows(MarketplaceCheckoutException.class,
                () -> service.createMarketplaceIntent("EVT-DROP", MarketplaceCheckoutType.DIRECT, actor(), request));

        assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatus());
        assertEquals("INVALID_REDIRECT_URL", rejected.getCode());
        verify(inventoryHoldService, never()).hold(any(), any(), any(), any(Integer.class), any(), any(), any(), any());
        verify(repository, never()).save(any(TossPaymentIntent.class));
    }

    @Test
    void keepsSameSitePathRedirectAndAppendsIntentId() {
        stubDirectDrop();
        when(repository.findByIdempotencyKey("IDEMP-1")).thenReturn(Optional.empty());
        when(repository.save(any(TossPaymentIntent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        MarketplaceCheckoutRequest request = marketplaceRequest(new BigDecimal("150000"));
        request.getPaymentInfo().setSuccessUrl("/app/?tossResult=success");

        TossPaymentIntentResponse response = service.createMarketplaceIntent(
                "EVT-DROP", MarketplaceCheckoutType.DIRECT, actor(), request);

        assertEquals("/app/?tossResult=success&intentId=" + response.getIntentId(), response.getSuccessUrl());
    }

    @Test
    void confirmRejectsGenericIntentCreatedForMarketplaceProductBeforeAuthorization() {
        TossPaymentIntent intent = baseIntent();
        when(repository.findById("TOSS-INTENT-1")).thenReturn(Optional.of(intent));
        when(listingRepository.existsByProductId("PROD-1")).thenReturn(true);

        MarketplaceCheckoutException rejected = assertThrows(MarketplaceCheckoutException.class,
                () -> service.confirm(TossPaymentConfirmRequest.builder()
                        .intentId("TOSS-INTENT-1")
                        .paymentKey("paykey_1")
                        .orderId("ORD-TOSS-1")
                        .amount(new BigDecimal("10000.00"))
                        .build()));

        assertEquals("MARKETPLACE_CHECKOUT_REQUIRED", rejected.getCode());
        verify(stateWriter, never()).authenticate(any());
        verify(completeReservationGateway, never()).processCompleteReservation(any());
    }

    private CompleteReservationRequest genericRequest(String productId) {
        return CompleteReservationRequest.builder()
                .productId(productId)
                .customerId("CUST-1")
                .quantity(1)
                .clientId("web")
                .idempotencyKey("IDEMP-GENERIC")
                .paymentInfo(CompleteReservationRequest.PaymentInfo.builder()
                        .amount(new BigDecimal("10000"))
                        .currency("KRW")
                        .paymentMethod("CREDIT_CARD")
                        .build())
                .build();
    }

    private void stubDirectDrop() {
        SaleEvent event = SaleEvent.builder()
                .saleEventId("EVT-DROP")
                .listingId("LIST-DROP")
                .sellerId("SELLER-1")
                .productId("PROD-DROP")
                .saleType(SaleType.DROP)
                .status(SaleEventStatus.LIVE)
                .startsAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5))
                .endsAt(LocalDateTime.now(ZoneOffset.UTC).plusHours(1))
                .price(new BigDecimal("150000"))
                .stockQuantity(10)
                .build();
        when(saleEventRepository.findById("EVT-DROP")).thenReturn(Optional.of(event));
        when(saleEventRepository.findByIdForUpdate("EVT-DROP")).thenReturn(Optional.of(event));
        when(listingRepository.findById("LIST-DROP")).thenReturn(Optional.of(MarketplaceListing.builder()
                .listingId("LIST-DROP")
                .sellerId("SELLER-1")
                .productId("PROD-DROP")
                .title("Limited Drop")
                .status(ListingStatus.ACTIVE)
                .build()));
        Inventory inventory = Inventory.builder()
                .productId("PROD-DROP")
                .totalQuantity(10)
                .availableQuantity(5)
                .reservedQuantity(0)
                .build();
        when(inventoryRepository.findById("PROD-DROP")).thenReturn(Optional.of(inventory));
        when(inventoryRepository.findByIdForUpdate("PROD-DROP")).thenReturn(Optional.of(inventory));
        when(repository.sumActiveMarketplaceQuantity(eq("EVT-DROP"), eq("DIRECT"), any(), any())).thenReturn(0L);
    }

    private MarketplaceCheckoutRequest marketplaceRequest(BigDecimal amount) {
        MarketplaceCheckoutRequest request = new MarketplaceCheckoutRequest();
        request.setCustomerId("CUST-1");
        request.setQuantity(1);
        request.setIdempotencyKey("IDEMP-1");
        request.setPaymentInfo(CompleteReservationRequest.PaymentInfo.builder()
                .amount(amount)
                .currency("KRW")
                .paymentMethod("CREDIT_CARD")
                .build());
        return request;
    }

    private CommerceActor actor() {
        return new CommerceActor("USER-1", "CUST-1");
    }

    private TossPaymentIntent baseIntent() {
        return TossPaymentIntent.builder()
                .intentId("TOSS-INTENT-1")
                .orderId("ORD-TOSS-1")
                .idempotencyKey("IDEMP-1")
                .requestHash("HASH")
                .customerId("CUST-1")
                .productId("PROD-1")
                .quantity(1)
                .amount(new BigDecimal("10000.00"))
                .currency("KRW")
                .paymentMethod("CREDIT_CARD")
                .orderName("EverySale order")
                .customerKey("CUST-1")
                .merchantId("SELLER-1")
                .clientId("client")
                .status("READY")
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .createdAt(LocalDateTime.now())
                .build();
    }

    private SaleEvent raffleEvent() {
        return SaleEvent.builder()
                .saleEventId("EVT-RAFFLE")
                .listingId("LIST-RAFFLE")
                .sellerId("SELLER-1")
                .productId("PROD-RAFFLE")
                .saleType(SaleType.RAFFLE)
                .status(SaleEventStatus.LIVE)
                .price(new BigDecimal("239000"))
                .build();
    }

    private MarketplaceListing raffleListing() {
        return MarketplaceListing.builder()
                .listingId("LIST-RAFFLE")
                .sellerId("SELLER-1")
                .productId("PROD-RAFFLE")
                .title("Limited Raffle")
                .status(ListingStatus.ACTIVE)
                .build();
    }

    private SaleEvent auctionEvent() {
        return SaleEvent.builder()
                .saleEventId("EVT-AUCTION")
                .listingId("LIST-AUCTION")
                .sellerId("SELLER-1")
                .productId("PROD-AUCTION")
                .saleType(SaleType.AUCTION)
                .status(SaleEventStatus.ENDED)
                .price(new BigDecimal("8500000"))
                .build();
    }

    private MarketplaceListing auctionListing() {
        return MarketplaceListing.builder()
                .listingId("LIST-AUCTION")
                .sellerId("SELLER-1")
                .productId("PROD-AUCTION")
                .title("Vintage Watch")
                .status(ListingStatus.ACTIVE)
                .build();
    }
}

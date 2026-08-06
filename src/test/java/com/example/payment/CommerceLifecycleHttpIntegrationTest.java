package com.example.payment;

import com.example.payment.domain.model.inventory.Inventory;
import com.example.payment.domain.model.marketplace.MarketplaceOrder;
import com.example.payment.domain.model.marketplace.MarketplaceOrderStatus;
import com.example.payment.domain.model.marketplace.SellerPayoutStatus;
import com.example.payment.domain.repository.InventoryMovementRepository;
import com.example.payment.domain.repository.InventoryRepository;
import com.example.payment.domain.repository.MarketplaceOrderRepository;
import com.example.payment.domain.repository.OutboxEventRepository;
import com.example.payment.domain.repository.SellerPayoutRepository;
import com.example.payment.infrastructure.util.ResourceReservationService;
import com.example.payment.presentation.dto.response.CompleteReservationResponse;
import com.example.payment.presentation.dto.response.TossPaymentIntentResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CommerceLifecycleHttpIntegrationTest extends FullCommerceTestcontainersSupport {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private MarketplaceOrderRepository orderRepository;

    @Autowired
    private SellerPayoutRepository payoutRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryMovementRepository movementRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ResourceReservationService resourceReservationService;

    @BeforeEach
    void resetStubCounters() {
        resetTossStub();
    }

    @AfterAll
    static void shutdownTossStub() {
        stopTossStub();
    }

    @Test
    void dropPurchaseUsesTemporalTossAndCancellationLedgersEndToEnd() throws Exception {
        String runId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        HttpHeaders sellerHeaders = headers("USER-SELLER-" + runId, "CUST-SELLER-" + runId, "CUSTOMER,SELLER");
        HttpHeaders buyerHeaders = headers("USER-BUYER-" + runId, "CUST-BUYER-" + runId, "CUSTOMER");
        HttpHeaders adminHeaders = headers("USER-ADMIN-" + runId, "CUST-ADMIN-" + runId, "ADMIN");

        Map<?, ?> seller = post("/api/me/seller-profile", sellerHeaders,
                Map.of("displayName", "E2E Seller " + runId), Map.class, HttpStatus.CREATED).getBody();
        assertNotNull(seller);
        String sellerId = String.valueOf(seller.get("sellerId"));

        post("/api/c2c/seller/verification", sellerHeaders, Map.of(
                "evidenceRef", "e2e://verification/" + runId,
                "note", "Commerce E2E verification"
        ), String.class, HttpStatus.OK);
        post("/api/admin/operations/queues/sellerVerifications/items/" + sellerId
                        + "/actions/approve",
                adminHeaders, Map.of("note", "Approved by commerce E2E"), String.class, HttpStatus.OK);

        Map<?, ?> listing = post("/api/c2c/listings", sellerHeaders, Map.of(
                "name", "E2E Drop " + runId,
                "description", "Commercial lifecycle test item",
                "price", 25000,
                "category", "OTHER",
                "quantity", 1,
                "itemCondition", "GOOD",
                "authenticityNote", "E2E fixture"
        ), Map.class, HttpStatus.CREATED).getBody();
        assertNotNull(listing);
        String listingId = String.valueOf(listing.get("listingId"));
        String productId = String.valueOf(listing.get("productId"));

        post("/api/c2c/listings/" + listingId + "/submit",
                sellerHeaders, null, String.class, HttpStatus.OK);
        post("/api/admin/operations/queues/listingReviews/items/" + listingId
                        + "/actions/approve",
                adminHeaders, Map.of("note", "Approved by commerce E2E"), String.class, HttpStatus.OK);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Map<?, ?> saleEvent = post(
                "/api/c2c/listings/" + listingId + "/sale-events",
                sellerHeaders,
                Map.of(
                        "saleType", "DROP",
                        "price", 25000,
                        "stockQuantity", 1,
                        "maxQuantityPerCustomer", 1,
                        "activationMode", "IMMEDIATE",
                        "startsAt", now.minusMinutes(1).toString(),
                        "endsAt", now.plusHours(2).toString()
                ),
                Map.class,
                HttpStatus.CREATED
        ).getBody();
        assertNotNull(saleEvent);
        String saleEventId = String.valueOf(saleEvent.get("saleEventId"));
        assertEquals("LIVE", saleEvent.get("saleEventStatus"));

        Map<String, Object> checkout = checkoutBody(runId, "CUST-BUYER-" + runId);
        ResponseEntity<String> selfTrade = post(
                "/api/marketplace/events/" + saleEventId + "/checkout/toss/intents",
                sellerHeaders, checkout, String.class);
        assertEquals(HttpStatus.FORBIDDEN, selfTrade.getStatusCode());
        assertTrue(selfTrade.getBody() != null && selfTrade.getBody().contains("SELF_TRADE_FORBIDDEN"));

        TossPaymentIntentResponse intent = post(
                "/api/marketplace/events/" + saleEventId + "/checkout/toss/intents",
                buyerHeaders, checkout, TossPaymentIntentResponse.class, HttpStatus.OK).getBody();
        assertNotNull(intent);

        Map<String, Object> confirmBody = Map.of(
                "intentId", intent.getIntentId(),
                "paymentKey", "pay_test_e2e_" + runId,
                "orderId", intent.getOrderId(),
                "amount", intent.getAmount()
        );
        CompleteReservationResponse terminal = awaitTerminalConfirm(buyerHeaders, confirmBody);
        assertEquals("SUCCESS", terminal.getStatus());

        CompleteReservationResponse replay = post(
                "/api/payments/toss/confirm", buyerHeaders, confirmBody,
                CompleteReservationResponse.class, HttpStatus.OK).getBody();
        assertNotNull(replay);
        assertEquals("SUCCESS", replay.getStatus());
        assertEquals(1, TOSS_CONFIRM_CALLS.get());

        Map<?, ?>[] buyerOrders = get(
                "/api/marketplace/customers/CUST-BUYER-" + runId + "/orders?page=0&size=10",
                buyerHeaders, Map[].class, HttpStatus.OK).getBody();
        assertNotNull(buyerOrders);
        assertEquals(1, buyerOrders.length);
        Map<?, ?> buyerOrder = buyerOrders[0];
        String marketplaceOrderId = String.valueOf(buyerOrder.get("marketplaceOrderId"));
        assertEquals("PAID", buyerOrder.get("status"));

        Map<?, ?> cancellation = post(
                "/api/c2c/orders/" + marketplaceOrderId + "/cancellations",
                buyerHeaders,
                Map.of("reason", "E2E buyer cancellation before shipment"),
                Map.class,
                HttpStatus.OK
        ).getBody();
        assertNotNull(cancellation);
        assertEquals("COMPLETED", cancellation.get("status"));
        assertNull(cancellation.get("failureReason"),
                () -> "Cancellation completed with deferred recovery: " + cancellation);
        assertEquals(1, TOSS_CANCEL_CALLS.get());

        MarketplaceOrder storedOrder = orderRepository.findById(marketplaceOrderId).orElseThrow();
        assertEquals(MarketplaceOrderStatus.REFUNDED, storedOrder.getStatus());
        assertEquals(SellerPayoutStatus.CANCELLED, payoutRepository
                .findBySourceTypeAndSourceId("MARKETPLACE_ORDER", storedOrder.getMarketplaceOrderId())
                .orElseThrow().getStatus());
        assertTrue(movementRepository.findByOperationKey(
                "order-cancel-restore-" + storedOrder.getMarketplaceOrderId()).isPresent(),
                () -> "Inventory movement was not committed for cancellation " + cancellation);

        Inventory inventory = inventoryRepository.findById(productId).orElseThrow();
        assertEquals(1, inventory.getAvailableQuantity());
        Map<String, Object> redisInventory = resourceReservationService.getResourceStatus(
                "inventory:" + productId);
        assertEquals(1, Integer.parseInt(String.valueOf(redisInventory.get("available"))));
        assertFalse(outboxEventRepository.findAll().stream()
                .filter(event -> storedOrder.getMarketplaceOrderId().equals(event.getAggregateId()))
                .toList().isEmpty());
        assertTrue(KAFKA.isRunning());
        assertTrue(TEMPORAL.isRunning());
    }

    private CompleteReservationResponse awaitTerminalConfirm(
            HttpHeaders headers, Map<String, Object> body) throws InterruptedException {
        CompleteReservationResponse response = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            ResponseEntity<CompleteReservationResponse> current = post(
                    "/api/payments/toss/confirm", headers, body, CompleteReservationResponse.class);
            response = current.getBody();
            if (response != null && !"PENDING".equals(response.getStatus())) {
                assertEquals(HttpStatus.OK, current.getStatusCode());
                return response;
            }
            Thread.sleep(250L);
        }
        throw new AssertionError("Temporal checkout did not reach a terminal state: "
                + (response != null ? response.getWorkflowId() : "no response"));
    }

    private Map<String, Object> checkoutBody(String runId, String customerId) {
        return Map.of(
                "customerId", customerId,
                "quantity", 1,
                "idempotencyKey", "E2E-INTENT-" + runId,
                "correlationId", "E2E-CORR-" + runId,
                "clientId", "commerce-e2e",
                "shippingInfo", Map.of(
                        "recipientName", "E2E Buyer",
                        "postalCode", "04524",
                        "address", "E2E test address",
                        "method", "PARCEL",
                        "contactPhone", "010-0000-0000"
                ),
                "paymentInfo", Map.of(
                        "amount", 25000,
                        "currency", "KRW",
                        "paymentMethod", "CREDIT_CARD",
                        "merchantId", "commerce-e2e",
                        "orderName", "E2E Drop " + runId
                )
        );
    }

    private HttpHeaders headers(String userId, String customerId, String roles) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-Id", "everysale-e2e");
        headers.set("X-Partner-Id", "commerce-e2e");
        headers.set("X-EverySale-User-Id", userId);
        headers.set("X-EverySale-Customer-Id", customerId);
        headers.set("X-EverySale-Roles", roles);
        return headers;
    }

    private <T> ResponseEntity<T> post(String path, HttpHeaders headers, Object body, Class<T> responseType) {
        return restTemplate.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, headers), responseType);
    }

    private <T> ResponseEntity<T> post(String path, HttpHeaders headers, Object body,
                                       Class<T> responseType, HttpStatus expectedStatus) {
        ResponseEntity<T> response = post(path, headers, body, responseType);
        assertEquals(expectedStatus, response.getStatusCode(),
                () -> "Unexpected response for POST " + path + ": " + response.getBody());
        return response;
    }

    private <T> ResponseEntity<T> get(String path, HttpHeaders headers,
                                      Class<T> responseType, HttpStatus expectedStatus) {
        ResponseEntity<T> response = restTemplate.exchange(
                url(path), HttpMethod.GET, new HttpEntity<>(headers), responseType);
        assertEquals(expectedStatus, response.getStatusCode());
        return response;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}

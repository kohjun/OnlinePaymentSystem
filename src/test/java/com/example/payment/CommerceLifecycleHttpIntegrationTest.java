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
import com.example.payment.presentation.dto.response.ListingMediaResponse;
import com.example.payment.presentation.dto.response.MarketplaceNotificationResponse;
import com.example.payment.presentation.dto.response.TossPaymentIntentResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
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
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "payment.recovery.fixed-delay-ms=86400000")
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

    @Autowired private com.example.payment.application.service.RefundLedgerWriter refundWriter;
    @Autowired private com.example.payment.domain.repository.RefundRecordRepository refunds;
    @Autowired private com.example.payment.domain.repository.PaymentRecordRepository payments;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.example.payment.infrastructure.messaging.outbox.OutboxEventService outboxService;
    private final java.util.concurrent.atomic.AtomicBoolean failApprovalCommit = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean approvalCommitFailed = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicInteger orderCreationFailures = new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean failOrderCreation;
    private final java.util.concurrent.atomic.AtomicBoolean crashApprovalDatabase = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicReference<String> crashedDatabase = new java.util.concurrent.atomic.AtomicReference<>();
    private java.util.concurrent.CountDownLatch databaseWriteFailed;
    @Autowired private io.temporal.client.WorkflowClient workflowClient;
    @Autowired private com.example.payment.infrastructure.temporal.TemporalProperties temporalProperties;
    @Autowired private com.example.payment.application.temporal.CompleteReservationActivitiesImpl activities;
    @Autowired private com.example.payment.domain.repository.TossPaymentIntentRepository intents;
    @Autowired private com.example.payment.application.service.InventoryHoldService holds;
    @Autowired private com.example.payment.application.service.PaymentObservationWriter paymentWriter;
    private io.temporal.worker.WorkerFactory isolatedWorker;
    private String originalTaskQueue;
    private com.example.recoveryfixture.RecoveryApplicationProcess childApplication;
    private com.example.recoveryfixture.RecoveryApplicationProcess peerApplication;
    private Integer originalPort;
    private java.nio.file.Path processDirectory;
    @Autowired private org.springframework.core.env.Environment environment;
    @Autowired private org.springframework.context.ApplicationContext applicationContext;
    @Autowired private tools.jackson.databind.ObjectMapper applicationMapper;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("redisObjectMapper")
    private tools.jackson.databind.ObjectMapper redisMapper;
    @Autowired private com.example.payment.infrastructure.persistence.redis.repository.CacheService cacheService;
    @Autowired private com.example.payment.application.service.TossPaymentIntentService tossIntentService;

    @Test
    void redisComponentsKeepTheirOwnMapperWhileApiUsesThePrimaryOne() {
        org.junit.jupiter.api.Assertions.assertNotSame(applicationMapper, redisMapper);
        org.junit.jupiter.api.Assertions.assertSame(redisMapper,
                org.springframework.test.util.ReflectionTestUtils.getField(cacheService, "objectMapper"));
        org.junit.jupiter.api.Assertions.assertSame(redisMapper,
                org.springframework.test.util.ReflectionTestUtils.getField(resourceReservationService, "objectMapper"));
        org.junit.jupiter.api.Assertions.assertSame(applicationMapper,
                org.springframework.test.util.ReflectionTestUtils.getField(tossIntentService, "objectMapper"));
    }

    enum CheckoutFault { NONE, RESPONSE_LOSS, APPROVAL_COMMIT_FAILURE, WORKER_REPLACEMENT, DATABASE_CRASH, PROCESS_RESTART, REFUND_PROCESS_RESTART, CANCEL_FIRST, SHIPMENT_FIRST, REFUND_BEFORE_PAYOUT, REFUND_DURING_PAYOUT, REFUND_AFTER_PAYOUT, PAYOUT_RESPONSE_LOSS, PAYOUT_PROCESS_RESTART, PAYOUT_RESTART_REFUND, DISPUTE_FIRST, CONFIRM_FIRST, DISPUTE_REFUND_BLOCKED, ORDER_CREATE_FAILURE, CLOSURE_ROLLBACK, AUTHORIZATION_RACE }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"FAILED", "UNKNOWN"})
    void refundLedgerAndOutboxSurviveOuterRollback(String status) {
        String paymentId = seedRecoveryPayment();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
                new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                    var begin = refundWriter.begin(paymentId, "ledger-" + paymentId, "RF-" + paymentId);
                    if ("UNKNOWN".equals(status)) refundWriter.unknown(paymentId, begin.refund(), "timeout", "test");
                    else refundWriter.failed(paymentId, begin.refund(), "rejected", "test");
                    throw new IllegalStateException("Caller failed after refund observation");
                }));
        assertEquals(status, refunds.findById("RF-" + paymentId).orElseThrow().getStatus());
        assertEquals("REFUND_" + status, payments.findById(paymentId).orElseThrow().getStatus());
        assertEquals(1, jdbc.queryForObject("select count(*) from outbox_events where aggregate_id=? and event_type=?",
                Integer.class, paymentId, "PAYMENT_REFUND_" + status));
    }

    @Test
    void differentConcurrentRefundKeysShareOneActiveOperation() throws Exception {
        String paymentId = seedRecoveryPayment();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var first = executor.submit(() -> { start.await(); return refundWriter.begin(paymentId, "A", "RF-A-" + paymentId); });
            var second = executor.submit(() -> { start.await(); return refundWriter.begin(paymentId, "B", "RF-B-" + paymentId); });
            start.countDown();
            var a = first.get(15, java.util.concurrent.TimeUnit.SECONDS);
            var b = second.get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(a.ready() ^ b.ready());
            assertEquals(a.refund().getRefundId(), b.refund().getRefundId());
            assertEquals(1, jdbc.queryForObject("select count(*) from refunds where payment_id=?", Integer.class, paymentId));
        } finally { executor.shutdownNow(); }
    }

    @Test
    void providerPartialRefundThenRemainingRefundImportsEachReceiptOnce() {
        String paymentId = seedRecoveryPayment();
        var partial = refundObservation(paymentId, "PARTIALLY_REFUNDED", "7000", java.util.List.of(
                new com.example.payment.application.dto.PaymentGatewayResult.ProviderCancellation(paymentId + "-C1", new java.math.BigDecimal("3000"), "DONE")));
        refundWriter.observe(paymentId, partial, null, false);
        var begin = refundWriter.begin(paymentId, "remaining", "RF-" + paymentId);
        assertEquals(0, new java.math.BigDecimal("7000").compareTo(begin.refund().getAmount()));
        var full = refundObservation(paymentId, "REFUNDED", "0", java.util.List.of(
                partial.getCancellations().get(0),
                new com.example.payment.application.dto.PaymentGatewayResult.ProviderCancellation(paymentId + "-C2", new java.math.BigDecimal("7000"), "DONE")));
        refundWriter.observe(paymentId, full, begin.refund(), false);
        refundWriter.observe(paymentId, full, begin.refund(), false);
        refundWriter.observe(paymentId, partial, null, false);
        assertEquals("REFUNDED", payments.findById(paymentId).orElseThrow().getStatus());
        assertEquals("SUCCEEDED", refunds.findById(begin.refund().getRefundId()).orElseThrow().getStatus());
        assertEquals(2, jdbc.queryForObject("select count(*) from provider_cancellations where payment_id=?", Integer.class, paymentId));
    }

    private com.example.payment.application.dto.PaymentGatewayResult refundObservation(String id, String status, String balance,
            java.util.List<com.example.payment.application.dto.PaymentGatewayResult.ProviderCancellation> cancellations) {
        return com.example.payment.application.dto.PaymentGatewayResult.builder().gatewayName("TOSS_PAYMENTS")
                .gatewayStatus(status).transactionId("KEY-" + id).orderId("ORD-" + id)
                .processedAmount(new java.math.BigDecimal("10000")).balanceAmount(new java.math.BigDecimal(balance))
                .currency("KRW").cancellations(cancellations).build();
    }

    private String seedRecoveryPayment() {
        String id = "REC-" + UUID.randomUUID();
        String product = inventoryRepository.findAll().get(0).getProductId();
        jdbc.update("insert into inventory_reservations(reservation_id,product_id,customer_id,quantity,status,expires_at,created_at,origin) values(?,?,?,1,'CONFIRMED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'CHECKOUT')",
                "RES-" + id, product, "RECOVERY-BUYER");
        jdbc.update("insert into orders(order_id,customer_id,product_id,reservation_id,quantity,amount,currency,status,created_at) values(?,?,?,?,1,10000,'KRW','PAID',CURRENT_TIMESTAMP)",
                "ORD-" + id, "RECOVERY-BUYER", product, "RES-" + id);
        jdbc.update("insert into payments(payment_id,order_id,reservation_id,customer_id,amount,currency,method,status,transaction_id,gateway_name,created_at) values(?,?,?,?,10000,'KRW','CREDIT_CARD','APPROVED',?,'TOSS_PAYMENTS',CURRENT_TIMESTAMP)",
                id, "ORD-" + id, "RES-" + id, "RECOVERY-BUYER", "KEY-" + id);
        return id;
    }

    @BeforeEach
    void resetStubCounters() {
        resetTossStub();
        failApprovalCommit.set(false);
        approvalCommitFailed.set(false);
        failOrderCreation = false;
        orderCreationFailures.set(0);
        crashApprovalDatabase.set(false);
        databaseWriteFailed = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(call -> {
            if ("ORDER_CREATED".equals(call.getArgument(2)) && failOrderCreation) {
                orderCreationFailures.incrementAndGet();
                throw new org.springframework.dao.TransientDataAccessResourceException("Injected order creation rollback");
            }
            if ("PAYMENT_PROCESSED".equals(call.getArgument(2)) && crashApprovalDatabase.compareAndSet(true, false)) {
                // Crash only this suite's application DB, after the approval update flush and before commit.
                String containerId = POSTGRES.getContainerId();
                crashedDatabase.set(containerId);
                var stopped = POSTGRES.execInContainer("su-exec", "postgres", "pg_ctl",
                        "-D", "/var/lib/postgresql/data", "-m", "immediate", "-w", "-t", "15", "stop");
                assertEquals(0, stopped.getExitCode(), stopped.getStderr());
                try {
                    return call.callRealMethod();
                } catch (Exception failure) {
                    databaseWriteFailed.countDown();
                    throw failure;
                }
            }
            if ("PAYMENT_PROCESSED".equals(call.getArgument(2)) && failApprovalCommit.compareAndSet(true, false)) {
                approvalCommitFailed.set(true);
                // The payment update has flushed, but its transaction has not committed yet.
                throw new org.springframework.dao.TransientDataAccessResourceException("Injected approval transaction failure");
            }
            return call.callRealMethod();
        }).when(outboxService).record(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap());
    }

    @AfterAll
    static void shutdownTossStub() {
        stopTossStub();
    }

    @org.junit.jupiter.api.AfterEach
    void stopIsolatedWorker() throws Exception {
        try {
            restartCrashedDatabase();
        } finally {
            try {
                try {
                    if (peerApplication != null) peerApplication.close();
                } finally {
                    if (childApplication != null) childApplication.close();
                }
                if (isolatedWorker != null) {
                    isolatedWorker.shutdownNow();
                    isolatedWorker.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS);
                    assertTrue(isolatedWorker.isTerminated(), "Isolated worker did not terminate");
                }
            } finally {
                if (originalTaskQueue != null) temporalProperties.setTaskQueue(originalTaskQueue);
                if (originalPort != null) port = originalPort;
                FAIL_LOOKUPS.set(0);
            }
        }
    }

    private void restartCrashedDatabase() throws Exception {
        String containerId = crashedDatabase.get();
        if (containerId == null) return;
        assertEquals(containerId, POSTGRES.getContainerId());
        var status = POSTGRES.execInContainer("su-exec", "postgres", "pg_ctl",
                "-D", "/var/lib/postgresql/data", "status");
        if (status.getExitCode() != 0) {
            var started = POSTGRES.execInContainer("su-exec", "postgres", "pg_ctl",
                    "-D", "/var/lib/postgresql/data", "-l", "/tmp/recovery-postgres.log", "-w", "-t", "30", "start");
            assertEquals(0, started.getExitCode(), started.getStderr());
        }
        String url = POSTGRES.getJdbcUrl();
        url += (url.contains("?") ? "&" : "?") + "connectTimeout=2&socketTimeout=2";
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(45).toNanos();
        java.sql.SQLException lastFailure = null;
        while (System.nanoTime() < deadline) {
            try (var connection = java.sql.DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("SELECT 1");
                crashedDatabase.compareAndSet(containerId, null);
                return;
            } catch (java.sql.SQLException failure) {
                lastFailure = failure;
                Thread.sleep(250);
            }
        }
        throw new AssertionError("Isolated PostgreSQL did not recover after crash", lastFailure);
    }

    private io.temporal.worker.WorkerFactory newIsolatedWorker() {
        var client = io.temporal.client.WorkflowClient.newInstance(workflowClient.getWorkflowServiceStubs(),
                io.temporal.client.WorkflowClientOptions.newBuilder()
                        .setNamespace(temporalProperties.getNamespace())
                        .setIdentity("recovery-worker-" + UUID.randomUUID()).build());
        var factory = io.temporal.worker.WorkerFactory.newInstance(client);
        var worker = factory.newWorker(temporalProperties.getTaskQueue());
        worker.registerWorkflowImplementationTypes(com.example.payment.application.temporal.CompleteReservationWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        factory.start();
        return factory;
    }

    @Test
    void approvalUncertaintySurvivesWorkerReplacementAndHistoryReplay() throws Exception {
        runDropPurchase(CheckoutFault.WORKER_REPLACEMENT);
    }

    @Test
    void approvalSurvivesPostgresCrashBeforeCommitWithoutReauthorization() throws Exception {
        runDropPurchase(CheckoutFault.DATABASE_CRASH);
    }

    @Test
    void approvalUncertaintySurvivesApplicationProcessRestart() throws Exception {
        runDropPurchase(CheckoutFault.PROCESS_RESTART);
    }

    @Test
    void committedRefundSurvivesApplicationProcessRestartWithoutRecancellation() throws Exception {
        runDropPurchase(CheckoutFault.REFUND_PROCESS_RESTART);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = CheckoutFault.class, names = {"CANCEL_FIRST", "SHIPMENT_FIRST"})
    void twoApplicationInstancesSerializeCancellationAndShipment(CheckoutFault fault) throws Exception {
        runDropPurchase(fault);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = CheckoutFault.class,
            names = {"REFUND_BEFORE_PAYOUT", "REFUND_DURING_PAYOUT", "REFUND_AFTER_PAYOUT"})
    void twoApplicationInstancesReconcilePayoutAndAdminRefund(CheckoutFault fault) throws Exception {
        runDropPurchase(fault);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = CheckoutFault.class,
            names = {"PAYOUT_RESPONSE_LOSS", "PAYOUT_PROCESS_RESTART", "PAYOUT_RESTART_REFUND"})
    void committedPayoutRecoversByLookupWithoutResubmission(CheckoutFault fault) throws Exception {
        runDropPurchase(fault);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = CheckoutFault.class,
            names = {"DISPUTE_FIRST", "CONFIRM_FIRST", "DISPUTE_REFUND_BLOCKED"})
    void disputeResolutionPreservesPayoutGuardsAcrossInstances(CheckoutFault fault) throws Exception {
        runDropPurchase(fault);
    }

    private com.example.recoveryfixture.RecoveryApplicationProcess startChildApplication(boolean recoverRefunds) throws Exception {
        return startChildApplication(recoverRefunds, false);
    }

    private com.example.recoveryfixture.RecoveryApplicationProcess startChildApplication(boolean recoverRefunds, boolean recoverPayouts) throws Exception {
        Map<String, String> configuration = new java.util.LinkedHashMap<>();
        for (String key : java.util.List.of("spring.datasource.url", "spring.datasource.username", "spring.datasource.password",
                "spring.datasource.driver-class-name", "spring.kafka.bootstrap-servers",
                "spring.data.redis.host", "spring.data.redis.port", "app.temporal.target", "app.temporal.namespace",
                "payment.toss.base-url", "payment.toss.client-key", "payment.toss.secret-key")) {
            configuration.put(key, environment.getRequiredProperty(key));
        }
        assertEquals("127.0.0.1", java.net.URI.create(configuration.get("payment.toss.base-url")).getHost());
        configuration.put("spring.config.location", "classpath:/application.yml");
        configuration.put("server.address", "127.0.0.1");
        configuration.put("server.port", "0");
        configuration.put("spring.datasource.hikari.data-source-properties.ApplicationName", "recovery-app-" + UUID.randomUUID());
        configuration.put("app.temporal.enabled", "true");
        configuration.put("app.temporal.worker-enabled", "true");
        configuration.put("app.temporal.task-queue", temporalProperties.getTaskQueue());
        configuration.put("app.outbox.enabled", "true");
        configuration.put("payment.toss.mode", "test");
        configuration.put("payment.default-gateway", "TOSS_PAYMENTS");
        configuration.put("payment.allow-gateway-fallback", "false");
        configuration.put("payment.mock.enabled", "false");
        configuration.put("payment.recovery.fixed-delay-ms", recoverRefunds ? "1000" : "86400000");
        configuration.put("app.payout.transfer.provider", "TOSS_PAYOUTS");
        configuration.put("app.payout.transfer.toss.base-url", configuration.get("payment.toss.base-url"));
        configuration.put("app.payout.transfer.toss.secret-key", "test_sk_payout_e2e");
        configuration.put("app.payout.transfer.toss.mode", "test");
        configuration.put("app.payout.transfer.toss.read-timeout-ms", "60000");
        configuration.put("app.payout.transfer.reconciliation.enabled", String.valueOf(recoverPayouts));
        configuration.put("app.payout.transfer.reconciliation.stale-seconds", "1");
        configuration.put("app.payout.transfer.reconciliation.initial-delay-ms", "1000");
        configuration.put("app.payout.transfer.reconciliation.fixed-delay-ms", "1000");
        configuration.put("app.checkout.public-complete-enabled", "false");
        configuration.put("app.checkout.legacy-reservation-api-enabled", "false");
        configuration.put("app.marketplace.realtime.redis-broadcast-enabled", "false");
        return com.example.recoveryfixture.RecoveryApplicationProcess.start(processDirectory, configuration);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = CheckoutFault.class,
            names = {"NONE", "RESPONSE_LOSS", "APPROVAL_COMMIT_FAILURE"})
    void dropPurchaseUsesTemporalTossAndCancellationLedgersEndToEnd(CheckoutFault fault) throws Exception {
        runDropPurchase(fault);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = CheckoutFault.class,
            names = {"ORDER_CREATE_FAILURE", "CLOSURE_ROLLBACK", "AUTHORIZATION_RACE"})
    void noAuthorizationProofReleasesFailedCheckoutAtomically(CheckoutFault fault) throws Exception {
        runDropPurchase(fault);
    }

    /**
     * 래플 추첨·경매 마감부터 낙찰자 결제, 주문·정산 원장까지.
     *
     * 낙찰자 재고 hold는 UTC 만료 시각으로 만들어진다. 서버 시간대가 UTC가 아니던
     * 시절에는 추첨·마감이 hold 생성에서 실패했고, 이 경로를 끝까지 타는 시나리오가 없어
     * 드러나지 않았다.
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "{0} confirmResponseLost={1}")
    @org.junit.jupiter.params.provider.CsvSource({"RAFFLE,false", "RAFFLE,true", "AUCTION,false", "AUCTION,true"})
    void winnerCheckoutCompletesThroughTemporalTossAndPayoutLedger(String saleType, boolean confirmResponseLost)
            throws Exception {
        String runId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        HttpHeaders sellerHeaders = headers("USER-SELLER-" + runId, "CUST-SELLER-" + runId, "CUSTOMER,SELLER");
        String winnerId = "CUST-WINNER-" + runId;
        HttpHeaders winnerHeaders = headers("USER-WINNER-" + runId, winnerId, "CUSTOMER");
        HttpHeaders outsiderHeaders = headers("USER-OUTSIDER-" + runId, "CUST-OUTSIDER-" + runId, "CUSTOMER");
        HttpHeaders adminHeaders = headers("USER-ADMIN-" + runId, "CUST-ADMIN-" + runId, "ADMIN");
        Map<String, String> listing = publishApprovedListing(runId, sellerHeaders, adminHeaders);
        String productId = listing.get("productId");

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Map<String, Object> eventBody = new java.util.HashMap<>(Map.of(
                "saleType", saleType,
                "price", 25000,
                "stockQuantity", 1,
                "maxQuantityPerCustomer", 1,
                "activationMode", "IMMEDIATE",
                "startsAt", now.minusMinutes(1).toString(),
                "endsAt", now.plusHours(2).toString()
        ));
        if ("AUCTION".equals(saleType)) eventBody.put("minBidIncrement", 1000);
        Map<?, ?> saleEvent = post("/api/c2c/listings/" + listing.get("listingId") + "/sale-events",
                sellerHeaders, eventBody, Map.class, HttpStatus.CREATED).getBody();
        assertNotNull(saleEvent);
        assertEquals("LIVE", saleEvent.get("saleEventStatus"));
        String eventPath = "/api/marketplace/events/" + saleEvent.get("saleEventId");
        // 브라우저가 UTC 시각을 현지 시각으로 오해하지 않도록 응답 시각에는 Z가 붙는다.
        Map<?, ?> publicEvent = get(eventPath, winnerHeaders, Map.class, HttpStatus.OK).getBody();
        assertNotNull(publicEvent);
        assertTrue(String.valueOf(publicEvent.get("endsAt")).endsWith("Z"), "endsAt: " + publicEvent.get("endsAt"));

        int amount;
        String checkoutPath;
        if ("RAFFLE".equals(saleType)) {
            post(eventPath + "/raffle/entries", winnerHeaders,
                    Map.of("idempotencyKey", "E2E-ENTRY-" + runId), String.class, HttpStatus.CREATED);
            post(eventPath + "/raffle/draw", adminHeaders, Map.of("winnerCount", 1), String.class, HttpStatus.OK);
            amount = 25000;
            checkoutPath = eventPath + "/raffle/winner-checkout/toss/intents";
        } else {
            post(eventPath + "/bids", winnerHeaders,
                    Map.of("bidAmount", 26000, "idempotencyKey", "E2E-BID-" + runId), String.class, HttpStatus.CREATED);
            post(eventPath + "/auction/close", adminHeaders, null, String.class, HttpStatus.OK);
            amount = 26000;
            checkoutPath = eventPath + "/auction/winner-checkout/toss/intents";
        }
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from inventory_reservations where product_id=? and status='RESERVED'",
                Integer.class, productId));
        String offerMessage = jdbc.queryForObject(
                "select message from marketplace_notifications where recipient_customer_id=? and notification_type=?",
                String.class, winnerId, saleType + "_WINNER_OFFERED");
        assertTrue(offerMessage != null && offerMessage.contains("(KST)"), offerMessage);

        Map<String, Object> checkout = new java.util.HashMap<>(checkoutBody(runId, winnerId));
        checkout.put("paymentInfo", Map.of("amount", amount, "currency", "KRW", "paymentMethod", "CREDIT_CARD"));
        assertEquals(HttpStatus.FORBIDDEN, post(checkoutPath, outsiderHeaders, checkout, String.class).getStatusCode());
        TossPaymentIntentResponse intent = post(checkoutPath, winnerHeaders, checkout,
                TossPaymentIntentResponse.class, HttpStatus.OK).getBody();
        assertNotNull(intent);

        Map<String, Object> confirmBody = Map.of(
                "intentId", intent.getIntentId(),
                "paymentKey", "pay_test_winner_" + runId,
                "orderId", intent.getOrderId(),
                "amount", intent.getAmount()
        );
        DROP_CONFIRM_RESPONSE.set(confirmResponseLost);
        FAIL_LOOKUPS.set(confirmResponseLost ? 1 : 0);
        CompleteReservationResponse terminal = awaitTerminalConfirm(winnerHeaders, confirmBody);
        assertEquals("SUCCESS", terminal.getStatus());
        assertEquals(1, TOSS_CONFIRM_CALLS.get());
        assertEquals(0, TOSS_CANCEL_CALLS.get());

        String orderStatus = jdbc.queryForObject(
                "select status from marketplace_orders where sale_event_id=? and customer_id=?",
                String.class, saleEvent.get("saleEventId"), winnerId);
        assertEquals("PAID", orderStatus);
        assertEquals("HELD", jdbc.queryForObject(
                "select p.status from seller_payouts p join marketplace_orders o on p.source_id = o.marketplace_order_id"
                        + " where o.sale_event_id=?", String.class, saleEvent.get("saleEventId")));
        assertEquals("RAFFLE".equals(saleType) ? "COMPLETED" : "PAID", jdbc.queryForObject(
                "RAFFLE".equals(saleType)
                        ? "select checkout_status from raffle_winners where sale_event_id=? and customer_id=?"
                        : "select status from auction_settlements where sale_event_id=? and customer_id=?",
                String.class, saleEvent.get("saleEventId"), winnerId));
        Inventory inventory = inventoryRepository.findById(productId).orElseThrow();
        assertEquals(0, inventory.getAvailableQuantity());
        assertEquals(0, inventory.getReservedQuantity());
        assertEquals(0, Integer.parseInt(String.valueOf(
                resourceReservationService.getResourceStatus("inventory:" + productId).get("reserved"))));

        CompleteReservationResponse replay = post("/api/payments/toss/confirm", winnerHeaders, confirmBody,
                CompleteReservationResponse.class, HttpStatus.OK).getBody();
        assertNotNull(replay);
        assertEquals("SUCCESS", replay.getStatus());
        assertEquals(1, TOSS_CONFIRM_CALLS.get());
    }

    private Map<String, String> publishApprovedListing(String runId, HttpHeaders sellerHeaders, HttpHeaders adminHeaders) {
        Map<?, ?> seller = post("/api/me/seller-profile", sellerHeaders,
                Map.of("displayName", "E2E Seller " + runId), Map.class, HttpStatus.CREATED).getBody();
        assertNotNull(seller);
        String sellerId = String.valueOf(seller.get("sellerId"));
        post("/api/c2c/seller/verification", sellerHeaders, Map.of(
                "evidenceRef", "e2e://verification/" + runId,
                "note", "Winner checkout E2E verification"
        ), String.class, HttpStatus.OK);
        post("/api/admin/operations/queues/sellerVerifications/items/" + sellerId + "/actions/approve",
                adminHeaders, Map.of("note", "Approved by winner checkout E2E"), String.class, HttpStatus.OK);

        Map<?, ?> listing = post("/api/c2c/listings", sellerHeaders, Map.of(
                "name", "E2E Winner Item " + runId,
                "description", "Winner checkout lifecycle test item",
                "price", 25000,
                "category", "OTHER",
                "quantity", 1,
                "itemCondition", "GOOD",
                "authenticityNote", "E2E fixture"
        ), Map.class, HttpStatus.CREATED).getBody();
        assertNotNull(listing);
        String listingId = String.valueOf(listing.get("listingId"));
        uploadMedia(listingId, sellerHeaders, "PRODUCT_IMAGE", "front.png",
                new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3});
        uploadMedia(listingId, sellerHeaders, "AUTHENTICITY_EVIDENCE", "receipt.pdf",
                "%PDF-1.7 winner-e2e".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        post("/api/c2c/listings/" + listingId + "/submit", sellerHeaders, null, String.class, HttpStatus.OK);
        post("/api/admin/operations/queues/listingReviews/items/" + listingId + "/actions/approve",
                adminHeaders, Map.of("note", "Approved by winner checkout E2E"), String.class, HttpStatus.OK);
        return Map.of("listingId", listingId, "productId", String.valueOf(listing.get("productId")));
    }

    private void runDropPurchase(CheckoutFault fault) throws Exception {
        boolean lostResponses = fault == CheckoutFault.RESPONSE_LOSS;
        boolean replaceWorker = fault == CheckoutFault.WORKER_REPLACEMENT;
        boolean crashDatabase = fault == CheckoutFault.DATABASE_CRASH;
        boolean restartProcess = fault == CheckoutFault.PROCESS_RESTART;
        boolean restartRefundProcess = fault == CheckoutFault.REFUND_PROCESS_RESTART;
        boolean multipleInstances = fault == CheckoutFault.CANCEL_FIRST || fault == CheckoutFault.SHIPMENT_FIRST;
        boolean disputeRace = fault == CheckoutFault.DISPUTE_FIRST || fault == CheckoutFault.CONFIRM_FIRST
                || fault == CheckoutFault.DISPUTE_REFUND_BLOCKED;
        boolean payoutRace = fault == CheckoutFault.REFUND_BEFORE_PAYOUT
                || fault == CheckoutFault.REFUND_DURING_PAYOUT || fault == CheckoutFault.REFUND_AFTER_PAYOUT
                || isPayoutRecovery(fault);
        if (replaceWorker) {
            originalTaskQueue = temporalProperties.getTaskQueue();
            temporalProperties.setTaskQueue("recovery-replacement-" + UUID.randomUUID());
            isolatedWorker = newIsolatedWorker();
        }
        if (restartProcess || restartRefundProcess || multipleInstances || payoutRace || disputeRace) {
            originalTaskQueue = temporalProperties.getTaskQueue();
            temporalProperties.setTaskQueue("recovery-process-" + UUID.randomUUID());
            originalPort = port;
            processDirectory = java.nio.file.Files.createTempDirectory(
                    java.nio.file.Path.of("build_sim_new").toAbsolutePath(), "recovery-process-");
            childApplication = startChildApplication(false);
            port = childApplication.port();
        }
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

        // 초안 리스팅도 상품과 재고가 먼저 생긴다. 일반 결제 경로로는 사 갈 수 없어야 한다.
        ResponseEntity<String> genericBypass = post("/api/payments/toss/intents", buyerHeaders,
                genericIntentBody(runId, productId), String.class);
        assertEquals(HttpStatus.CONFLICT, genericBypass.getStatusCode());
        // 프론트는 오류 코드를 최상위 code에서 읽는다(ProblemDetail이 properties로 묻히면 안 된다).
        assertTrue(genericBypass.getBody() != null
                && genericBypass.getBody().contains("\"code\":\"MARKETPLACE_REQUEST_REJECTED\""), genericBypass.getBody());

        ListingMediaResponse[] productImages = uploadMedia(
                listingId, sellerHeaders, "PRODUCT_IMAGE", "front.png",
                new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3});
        assertEquals(1, productImages.length);
        assertTrue(productImages[0].getUrl().contains("/api/c2c/listings/"));
        ListingMediaResponse[] evidence = uploadMedia(
                listingId, sellerHeaders, "AUTHENTICITY_EVIDENCE", "receipt.pdf",
                "%PDF-1.7 commerce-e2e".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals(1, evidence.length);
        get("/api/media/listings/" + listingId + "/images", sellerHeaders,
                String.class, HttpStatus.NOT_FOUND);

        post("/api/c2c/listings/" + listingId + "/submit",
                sellerHeaders, null, String.class, HttpStatus.OK);
        post("/api/admin/operations/queues/listingReviews/items/" + listingId
                        + "/actions/approve",
                adminHeaders, Map.of("note", "Approved by commerce E2E"), String.class, HttpStatus.OK);
        ListingMediaResponse[] publicImages = get(
                "/api/media/listings/" + listingId + "/images", buyerHeaders,
                ListingMediaResponse[].class, HttpStatus.OK).getBody();
        assertNotNull(publicImages);
        assertEquals(1, publicImages.length);
        assertTrue(publicImages[0].getUrl().contains("/api/media/product-images/"));

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
        if (fault == CheckoutFault.ORDER_CREATE_FAILURE || fault == CheckoutFault.CLOSURE_ROLLBACK
                || fault == CheckoutFault.AUTHORIZATION_RACE) {
            verifyEarlyOrderFailure(fault, buyerHeaders, intent, confirmBody, productId);
            return;
        }
        DROP_CONFIRM_RESPONSE.set(lostResponses || replaceWorker || restartProcess);
        FAIL_LOOKUPS.set(replaceWorker || crashDatabase || restartProcess ? 10000 : lostResponses ? 1 : 0);
        failApprovalCommit.set(fault == CheckoutFault.APPROVAL_COMMIT_FAILURE);
        crashApprovalDatabase.set(crashDatabase);
        String originalWorkflowId = null;
        if (crashDatabase) {
            var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                var firstConfirm = executor.submit(() -> post("/api/payments/toss/confirm",
                        buyerHeaders, confirmBody, String.class));
                assertTrue(databaseWriteFailed.await(45, java.util.concurrent.TimeUnit.SECONDS),
                        "Expected a real database write failure after provider approval");
                assertTrue(POSTGRES.isRunning(), "Container must retain its original network endpoint");
                assertEquals(3, POSTGRES.execInContainer("su-exec", "postgres", "pg_ctl",
                        "-D", "/var/lib/postgresql/data", "status").getExitCode());
                assertEquals("DONE", TOSS_PAYMENTS.get(confirmBody.get("paymentKey")).get("status"));
                assertEquals(1, TOSS_CONFIRM_CALLS.get());
                assertEquals(0, TOSS_CANCEL_CALLS.get());
                assertEquals(1, Integer.parseInt(String.valueOf(resourceReservationService
                        .getResourceStatus("inventory:" + productId).get("reserved"))));
                restartCrashedDatabase();
                var initialResponse = firstConfirm.get(45, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(initialResponse.getStatusCode().value() == 202 || initialResponse.getStatusCode().is5xxServerError(),
                        "Unconfirmed payment must not return success: " + initialResponse.getStatusCode());
                var storedIntent = intents.findById(intent.getIntentId()).orElseThrow();
                var storedPayment = payments.findByReservationId(storedIntent.getInventoryReservationId()).orElseThrow();
                assertTrue(java.util.Set.of("PROCESSING", "UNKNOWN").contains(storedPayment.getStatus()));
                assertEquals(0, inventoryRepository.findById(productId).orElseThrow().getAvailableQuantity());
                assertFalse(holds.release(storedIntent.getInventoryReservationId(), "EXPIRED"));
                assertEquals(0, jdbc.queryForObject(
                        "select count(*) from outbox_events where aggregate_id=? and event_type='PAYMENT_PROCESSED'",
                        Integer.class, storedPayment.getPaymentId()));
            } finally {
                try {
                    restartCrashedDatabase();
                } finally {
                    FAIL_LOOKUPS.set(0);
                    executor.shutdownNow();
                    assertTrue(executor.awaitTermination(45, java.util.concurrent.TimeUnit.SECONDS));
                }
            }
        }
        if (replaceWorker || restartProcess) {
            var pending = post("/api/payments/toss/confirm", buyerHeaders, confirmBody,
                    CompleteReservationResponse.class, HttpStatus.ACCEPTED).getBody();
            assertNotNull(pending);
            assertEquals("PENDING", pending.getStatus());
            originalWorkflowId = pending.getWorkflowId();
            assertNotNull(originalWorkflowId);
            assertEquals(1, TOSS_CONFIRM_CALLS.get());
            long oldPid = restartProcess ? childApplication.pid() : 0;
            if (restartProcess) {
                childApplication.close();
                assertFalse(childApplication.isAlive());
            } else {
                isolatedWorker.shutdownNow();
                isolatedWorker.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(isolatedWorker.isTerminated());
            }
            var storedIntent = intents.findById(intent.getIntentId()).orElseThrow();
            var storedPayment = payments.findByReservationId(storedIntent.getInventoryReservationId()).orElseThrow();
            assertEquals("UNKNOWN", storedPayment.getStatus());
            assertEquals("DONE", TOSS_PAYMENTS.get(confirmBody.get("paymentKey")).get("status"));
            assertEquals(0, TOSS_CANCEL_CALLS.get());
            assertEquals(0L, outboxEventRepository.findAll().stream()
                    .filter(event -> storedPayment.getPaymentId().equals(event.getAggregateId())
                            && "PAYMENT_PROCESSED".equals(event.getEventType())).count());
            assertEquals(0, inventoryRepository.findById(productId).orElseThrow().getAvailableQuantity());
            assertFalse(holds.release(storedIntent.getInventoryReservationId(), "EXPIRED"));
            assertEquals(1, Integer.parseInt(String.valueOf(resourceReservationService
                    .getResourceStatus("inventory:" + productId).get("reserved"))));
            if (restartProcess) {
                childApplication = startChildApplication(false);
                assertTrue(childApplication.isAlive());
                assertTrue(oldPid != childApplication.pid());
                port = childApplication.port();
                get("/api/payments/toss/intents/" + intent.getIntentId(), sellerHeaders,
                        String.class, HttpStatus.FORBIDDEN);
                var pendingAfterRestart = get("/api/payments/toss/intents/" + intent.getIntentId(), buyerHeaders,
                        CompleteReservationResponse.class, HttpStatus.OK).getBody();
                assertNotNull(pendingAfterRestart);
                assertEquals("PENDING", pendingAfterRestart.getStatus());
                assertEquals(originalWorkflowId, pendingAfterRestart.getWorkflowId());
            } else {
                isolatedWorker = newIsolatedWorker();
            }
            FAIL_LOOKUPS.set(0);
        }
        CompleteReservationResponse terminal = restartProcess
                ? awaitTerminalStatus(buyerHeaders, intent.getIntentId())
                : awaitTerminalConfirm(buyerHeaders, confirmBody);
        assertEquals("SUCCESS", terminal.getStatus());
        assertEquals(fault == CheckoutFault.APPROVAL_COMMIT_FAILURE, approvalCommitFailed.get());
        if (replaceWorker || restartProcess) {
            assertEquals(originalWorkflowId, terminal.getWorkflowId());
            io.temporal.testing.WorkflowReplayer.replayWorkflowExecution(
                    workflowClient.fetchHistory(terminal.getWorkflowId()),
                    com.example.payment.application.temporal.CompleteReservationWorkflowImpl.class);
        }

        CompleteReservationResponse replay = post(
                "/api/payments/toss/confirm", buyerHeaders, confirmBody,
                CompleteReservationResponse.class, HttpStatus.OK).getBody();
        assertNotNull(replay);
        assertEquals("SUCCESS", replay.getStatus());
        assertEquals(1, TOSS_CONFIRM_CALLS.get());
        assertNotNull(terminal.getPayment());
        assertEquals(1L, outboxEventRepository.findAll().stream()
                .filter(event -> terminal.getPayment().getPaymentId().equals(event.getAggregateId())
                        && "PAYMENT_PROCESSED".equals(event.getEventType())).count());

        MarketplaceNotificationResponse.Inbox paidInbox = get(
                "/api/c2c/notifications?page=0&size=30", buyerHeaders,
                MarketplaceNotificationResponse.Inbox.class, HttpStatus.OK).getBody();
        assertNotNull(paidInbox);
        assertTrue(paidInbox.getUnreadCount() > 0);
        assertTrue(paidInbox.getNotifications().stream()
                .anyMatch(notification -> "PAYMENT_COMPLETED".equals(notification.getNotificationType())));

        Map<?, ?>[] buyerOrders = get(
                "/api/marketplace/customers/CUST-BUYER-" + runId + "/orders?page=0&size=10",
                buyerHeaders, Map[].class, HttpStatus.OK).getBody();
        assertNotNull(buyerOrders);
        assertEquals(1, buyerOrders.length);
        Map<?, ?> buyerOrder = buyerOrders[0];
        String marketplaceOrderId = String.valueOf(buyerOrder.get("marketplaceOrderId"));
        assertEquals("PAID", buyerOrder.get("status"));

        if (disputeRace) {
            verifyDisputeResolution(fault, buyerHeaders, sellerHeaders, adminHeaders, sellerId, marketplaceOrderId, productId);
            return;
        }

        if (payoutRace) {
            racePayoutAndAdminRefund(fault, buyerHeaders, sellerHeaders, adminHeaders,
                    sellerId, marketplaceOrderId, productId);
            return;
        }
        if (multipleInstances) {
            raceCancellationAndShipment(fault, buyerHeaders, sellerHeaders, adminHeaders,
                    sellerId, marketplaceOrderId, productId);
            if (fault == CheckoutFault.SHIPMENT_FIRST) return;
        }
        if (restartRefundProcess) {
            restartDuringRefund(buyerHeaders, marketplaceOrderId, terminal.getPayment().getPaymentId(),
                    productId, String.valueOf(confirmBody.get("paymentKey")));
        }
        DROP_CANCEL_RESPONSE.set(lostResponses);
        Map<?, ?> cancellation = post(
                "/api/c2c/orders/" + marketplaceOrderId + "/cancellations",
                buyerHeaders,
                Map.of("reason", "E2E buyer cancellation before shipment"),
                Map.class,
                lostResponses ? HttpStatus.ACCEPTED : HttpStatus.OK
        ).getBody();
        assertNotNull(cancellation);
        if (lostResponses) {
            assertEquals("REFUND_UNKNOWN", cancellation.get("status"));
            assertEquals(0, inventoryRepository.findById(productId).orElseThrow().getAvailableQuantity());
            assertEquals(SellerPayoutStatus.HELD, payoutRepository.findBySourceTypeAndSourceId("MARKETPLACE_ORDER", marketplaceOrderId).orElseThrow().getStatus());
            cancellation = post("/api/c2c/orders/" + marketplaceOrderId + "/cancellations", buyerHeaders,
                    Map.of("reason", "recheck existing cancellation"), Map.class, HttpStatus.OK).getBody();
        }
        assertEquals("COMPLETED", cancellation.get("status"));
        assertNull(cancellation.get("failureReason"),
                "Cancellation completed with deferred recovery");
        assertEquals(1, TOSS_CANCEL_CALLS.get());

        MarketplaceOrder storedOrder = orderRepository.findById(marketplaceOrderId).orElseThrow();
        assertEquals(MarketplaceOrderStatus.REFUNDED, storedOrder.getStatus());
        assertEquals(SellerPayoutStatus.CANCELLED, payoutRepository
                .findBySourceTypeAndSourceId("MARKETPLACE_ORDER", storedOrder.getMarketplaceOrderId())
                .orElseThrow().getStatus());
        assertTrue(movementRepository.findByOperationKey(
                "order-cancel-restore-" + storedOrder.getMarketplaceOrderId()).isPresent(),
                "Inventory movement was not committed for cancellation");
        if (restartRefundProcess) {
            assertEquals("REFUNDED", jdbc.queryForObject(
                    "select status from orders where order_id=?", String.class, storedOrder.getOrderId()));
            assertEquals("CANCELLED", storedOrder.getFulfillmentStatus().name());
            assertEquals(1, jdbc.queryForObject(
                    "select count(*) from refunds where payment_id=?", Integer.class, storedOrder.getPaymentId()));
            assertEquals(1, jdbc.queryForObject(
                    "select count(*) from outbox_events where aggregate_id=? and event_type='PAYMENT_REFUND_OBSERVED'",
                    Integer.class, storedOrder.getPaymentId()));
            assertEquals(1, jdbc.queryForObject(
                    "select count(*) from inventory_movements where operation_key=?",
                    Integer.class, "order-cancel-restore-" + storedOrder.getMarketplaceOrderId()));
        }

        Inventory inventory = inventoryRepository.findById(productId).orElseThrow();
        assertEquals(1, inventory.getAvailableQuantity());
        Map<String, Object> redisInventory = resourceReservationService.getResourceStatus(
                "inventory:" + productId);
        assertEquals(1, Integer.parseInt(String.valueOf(redisInventory.get("available"))));
        assertFalse(outboxEventRepository.findAll().stream()
                .filter(event -> storedOrder.getMarketplaceOrderId().equals(event.getAggregateId()))
                .toList().isEmpty());

        Map<?, ?> paused = post("/api/c2c/listings/" + listingId + "/pause",
                sellerHeaders, null, Map.class, HttpStatus.OK).getBody();
        assertNotNull(paused);
        assertEquals("PAUSED", paused.get("status"));
        Map<?, ?> resumed = post("/api/c2c/listings/" + listingId + "/resume",
                sellerHeaders, null, Map.class, HttpStatus.OK).getBody();
        assertNotNull(resumed);
        assertEquals("ACTIVE", resumed.get("status"));

        MarketplaceNotificationResponse.Inbox cancellationInbox = get(
                "/api/c2c/notifications?page=0&size=30", buyerHeaders,
                MarketplaceNotificationResponse.Inbox.class, HttpStatus.OK).getBody();
        assertNotNull(cancellationInbox);
        assertTrue(cancellationInbox.getNotifications().stream()
                .anyMatch(notification -> "ORDER_CANCELLATION_UPDATED".equals(notification.getNotificationType())));
        assertTrue(KAFKA.isRunning());
        assertTrue(TEMPORAL.isRunning());
    }

    private void verifyDisputeResolution(CheckoutFault fault, HttpHeaders buyer, HttpHeaders seller,
                                         HttpHeaders admin, String sellerId, String orderId, String productId) throws Exception {
        peerApplication = startChildApplication(false);
        assertTrue(childApplication.pid() != peerApplication.pid());
        var shipment = requestAt(port, "PATCH", "/api/sellers/" + sellerId + "/orders/" + orderId + "/fulfillment",
                seller, Map.of("fulfillmentStatus", "SHIPPED", "trackingCarrier", "E2E", "trackingNumber", "TRACK-" + orderId));
        assertEquals(HttpStatus.OK, shipment.getStatusCode(), shipment.getBody());
        String disputePath = "/api/c2c/orders/" + orderId + "/dispute";
        String confirmPath = "/api/c2c/orders/" + orderId + "/confirm-delivery";
        String resolutionPath = disputePath + "/resolve";
        var payout = payoutRepository.findBySourceTypeAndSourceId("MARKETPLACE_ORDER", orderId).orElseThrow();
        String releasePath = "/api/sellers/" + sellerId + "/payouts/" + payout.getPayoutId() + "/release";
        Map<String, Object> resolution = Map.of("resolution", "PAYOUT_READY", "note", "Seller delivery evidence verified");
        post(resolutionPath, seller, resolution, String.class, HttpStatus.FORBIDDEN);
        post(resolutionPath, buyer, resolution, String.class, HttpStatus.FORBIDDEN);
        post(disputePath, seller, Map.of("reason", "Not the order buyer"), String.class, HttpStatus.BAD_REQUEST);
        assertNull(orderRepository.findById(orderId).orElseThrow().getDisputedAt());

        if (fault == CheckoutFault.DISPUTE_REFUND_BLOCKED) {
            post(disputePath, buyer, Map.of("reason", "Dispute with uncertain refund"), String.class, HttpStatus.OK);
            String paymentId = orderRepository.findById(orderId).orElseThrow().getPaymentId();
            DROP_CANCEL_RESPONSE.set(true);
            try {
                post("/api/admin/payments/" + paymentId + "/refund", admin,
                        Map.of("idempotencyKey", "dispute-refund-" + orderId, "reason", "Refund uncertainty drill"),
                        String.class, HttpStatus.BAD_GATEWAY);
                FAIL_LOOKUPS.set(10000);
                assertEquals("UNKNOWN", jdbc.queryForObject("select status from refunds where payment_id=?", String.class, paymentId));
                var rejected = post(resolutionPath, admin, resolution, String.class, HttpStatus.CONFLICT);
                assertTrue(rejected.getBody().contains("REFUND_VERIFICATION_REQUIRED"));
                var unresolved = orderRepository.findById(orderId).orElseThrow();
                assertNull(unresolved.getDisputeResolvedAt());
                assertNull(unresolved.getDisputeResolvedBy());
                assertNull(unresolved.getDisputeResolution());
                assertNull(unresolved.getFulfilledAt());
                assertEquals(SellerPayoutStatus.DISPUTED, payoutRepository.findById(payout.getPayoutId()).orElseThrow().getStatus());
                post(releasePath, admin, Map.of(), String.class, HttpStatus.BAD_REQUEST);
                assertEquals(0, PAYOUT_SUBMIT_CALLS.get());
                assertEquals(1, TOSS_CANCEL_CALLS.get());
                assertEquals(0, inventoryRepository.findById(productId).orElseThrow().getAvailableQuantity());
                assertEquals(0, Integer.parseInt(String.valueOf(resourceReservationService.getResourceStatus("inventory:" + productId).get("available"))));
            } finally {
                FAIL_LOOKUPS.set(0);
                DROP_CANCEL_RESPONSE.set(false);
            }
            return;
        }

        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<ResponseEntity<String>> disputeCall = () -> requestAt(childApplication.port(),
                    "POST", disputePath, buyer, Map.of("reason", "Concurrent dispute"));
            java.util.concurrent.Callable<ResponseEntity<String>> confirmCall = () -> requestAt(peerApplication.port(),
                    "POST", confirmPath, buyer, Map.of());
            java.util.concurrent.Future<ResponseEntity<String>> dispute;
            java.util.concurrent.Future<ResponseEntity<String>> confirmation;
            try (var lock = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
                lock.setAutoCommit(false);
                try (var statement = lock.prepareStatement("select marketplace_order_id from marketplace_orders where marketplace_order_id=? for update")) {
                    statement.setString(1, orderId);
                    statement.executeQuery().close();
                }
                if (fault == CheckoutFault.DISPUTE_FIRST) {
                    dispute = executor.submit(disputeCall);
                    awaitBlockedApplicationSessions(1);
                    confirmation = executor.submit(confirmCall);
                } else {
                    confirmation = executor.submit(confirmCall);
                    awaitBlockedApplicationSessions(1);
                    dispute = executor.submit(disputeCall);
                }
                awaitBlockedApplicationSessions(2);
                post(releasePath, admin, Map.of(), String.class, HttpStatus.BAD_REQUEST);
                lock.commit();
            }
            var disputeResponse = dispute.get(30, java.util.concurrent.TimeUnit.SECONDS);
            var confirmResponse = confirmation.get(30, java.util.concurrent.TimeUnit.SECONDS);
            boolean disputeFirst = fault == CheckoutFault.DISPUTE_FIRST;
            assertEquals(disputeFirst ? HttpStatus.OK : HttpStatus.BAD_REQUEST, disputeResponse.getStatusCode(), disputeResponse.getBody());
            assertEquals(disputeFirst ? HttpStatus.BAD_REQUEST : HttpStatus.OK, confirmResponse.getStatusCode(), confirmResponse.getBody());
            if (disputeFirst) {
                assertNull(orderRepository.findById(orderId).orElseThrow().getBuyerConfirmedAt());
                assertEquals(SellerPayoutStatus.DISPUTED, payoutRepository.findById(payout.getPayoutId()).orElseThrow().getStatus());
                post(releasePath, admin, Map.of(), String.class, HttpStatus.BAD_REQUEST);
                post(resolutionPath, admin, resolution, String.class, HttpStatus.OK);
                var resolved = orderRepository.findById(orderId).orElseThrow();
                assertNotNull(resolved.getDisputeResolvedAt());
                assertEquals(admin.getFirst("X-EverySale-User-Id"), resolved.getDisputeResolvedBy());
                assertEquals("PAYOUT_READY", resolved.getDisputeResolution().name());
                assertEquals("DELIVERED", resolved.getFulfillmentStatus().name());
                assertEquals(SellerPayoutStatus.READY_FOR_RELEASE, payoutRepository.findById(payout.getPayoutId()).orElseThrow().getStatus());
                // Replays across the other JVM cannot replace the original audit fields.
                assertEquals(HttpStatus.OK, requestAt(peerApplication.port(), "POST", resolutionPath, admin,
                        Map.of("resolution", "PAYOUT_READY", "note", "Repeated resolution")).getStatusCode());
                assertEquals(resolved.getDisputeResolvedAt(), orderRepository.findById(orderId).orElseThrow().getDisputeResolvedAt());
                assertEquals(resolved.getDisputeResolutionNote(), orderRepository.findById(orderId).orElseThrow().getDisputeResolutionNote());
            } else {
                assertNull(orderRepository.findById(orderId).orElseThrow().getDisputedAt());
                assertNotNull(orderRepository.findById(orderId).orElseThrow().getBuyerConfirmedAt());
                post(resolutionPath, admin, resolution, String.class, HttpStatus.BAD_REQUEST);
            }
            post("/api/c2c/seller/payout-account", seller, Map.of("accountRef", "stub-account-" + sellerId,
                    "bankCode", "E2E", "bankName", "Stub Bank", "accountHolderName", "E2E Seller", "accountLast4", "1234"),
                    String.class, HttpStatus.OK);
            post("/api/sellers/moderation/" + sellerId + "/payout-account", admin,
                    Map.of("approved", true, "note", "Stub account review"), String.class, HttpStatus.OK);
            post(releasePath, admin, Map.of(), String.class, HttpStatus.OK);
            assertEquals(HttpStatus.OK, requestAt(peerApplication.port(), "POST", releasePath, admin, Map.of()).getStatusCode());
            if (disputeFirst) post(resolutionPath, admin, resolution, String.class, HttpStatus.OK);
            assertEquals(SellerPayoutStatus.RELEASED, payoutRepository.findById(payout.getPayoutId()).orElseThrow().getStatus());
            assertEquals(1, PAYOUT_SUBMIT_CALLS.get());
            assertEquals(0, TOSS_CANCEL_CALLS.get());
            assertEquals(1, jdbc.queryForObject("select count(*) from seller_payout_transfers where payout_id=?", Integer.class, payout.getPayoutId()));
            assertEquals(0, inventoryRepository.findById(productId).orElseThrow().getAvailableQuantity());
            assertEquals(0, Integer.parseInt(String.valueOf(resourceReservationService.getResourceStatus("inventory:" + productId).get("available"))));
            assertTrue(movementRepository.findByOperationKey("order-cancel-restore-" + orderId).isEmpty());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    private void racePayoutAndAdminRefund(CheckoutFault fault, HttpHeaders buyer, HttpHeaders seller,
                                        HttpHeaders admin, String sellerId, String orderId, String productId) throws Exception {
        if (!isPayoutRecovery(fault)) {
            peerApplication = startChildApplication(true);
            assertTrue(childApplication.pid() != peerApplication.pid());
        }
        post("/api/c2c/seller/payout-account", seller, Map.of("accountRef", "stub-account-" + sellerId,
                "bankCode", "E2E", "bankName", "Stub Bank", "accountHolderName", "E2E Seller",
                "accountLast4", "1234"), String.class, HttpStatus.OK);
        post("/api/sellers/moderation/" + sellerId + "/payout-account", admin,
                Map.of("approved", true, "note", "Stub account review"), String.class, HttpStatus.OK);
        var shipment = requestAt(port, "PATCH", "/api/sellers/" + sellerId + "/orders/" + orderId + "/fulfillment",
                seller, Map.of("fulfillmentStatus", "SHIPPED", "trackingCarrier", "E2E", "trackingNumber", "TRACK-" + orderId));
        assertEquals(HttpStatus.OK, shipment.getStatusCode(), shipment.getBody());
        post("/api/c2c/orders/" + orderId + "/confirm-delivery", buyer, Map.of(), String.class, HttpStatus.OK);
        var payout = payoutRepository.findBySourceTypeAndSourceId("MARKETPLACE_ORDER", orderId).orElseThrow();
        assertEquals(SellerPayoutStatus.READY_FOR_RELEASE, payout.getStatus());
        var order = orderRepository.findById(orderId).orElseThrow();
        String releasePath = "/api/sellers/" + sellerId + "/payouts/" + payout.getPayoutId() + "/release";
        String refundPath = "/api/admin/payments/" + order.getPaymentId() + "/refund";
        Map<String, Object> refundBody = Map.of("idempotencyKey", "admin-refund-" + orderId, "reason", "Payout race drill");
        if (isPayoutRecovery(fault)) {
            recoverCommittedPayout(fault, admin, orderId, productId, payout.getPayoutId(), releasePath, refundPath, refundBody);
            return;
        }
        var barrier = new CancelResponseBarrier();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<ResponseEntity<String>> pendingRelease = null;
            if (fault == CheckoutFault.REFUND_DURING_PAYOUT) {
                PAYOUT_RESPONSE_BARRIER.set(barrier);
                pendingRelease = executor.submit(() -> requestAt(port, "POST", releasePath, admin, Map.of()));
                assertTrue(barrier.providerCommitted.await(30, java.util.concurrent.TimeUnit.SECONDS),
                        "Outgoing payout did not reach the provider");
                assertEquals("PROCESSING", jdbc.queryForObject(
                        "select status from seller_payout_transfers where payout_id=?", String.class, payout.getPayoutId()));
            } else if (fault == CheckoutFault.REFUND_AFTER_PAYOUT) {
                var release = requestAt(port, "POST", releasePath, admin, Map.of());
                assertEquals(HttpStatus.OK, release.getStatusCode(), release.getBody());
                assertEquals(SellerPayoutStatus.RELEASED, payoutRepository.findById(payout.getPayoutId()).orElseThrow().getStatus());
            }

            var refund = requestAt(peerApplication.port(), "POST", refundPath, admin, refundBody);
            assertEquals(HttpStatus.OK, refund.getStatusCode(), refund.getBody());
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
            while (orderRepository.findById(orderId).orElseThrow().getStatus() != MarketplaceOrderStatus.REFUNDED) {
                assertTrue(System.nanoTime() < deadline, "Scheduled refund recovery did not finalize the order");
                Thread.sleep(100);
            }
            if (pendingRelease != null) {
                assertEquals(SellerPayoutStatus.CANCELLED, payoutRepository.findById(payout.getPayoutId()).orElseThrow().getStatus());
                barrier.releaseResponse.countDown();
                var release = pendingRelease.get(20, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(HttpStatus.OK, release.getStatusCode(), release.getBody());
            }

            boolean transferred = fault != CheckoutFault.REFUND_BEFORE_PAYOUT;
            var expected = transferred ? SellerPayoutStatus.RECOVERY_REQUIRED : SellerPayoutStatus.CANCELLED;
            assertEquals(expected, payoutRepository.findById(payout.getPayoutId()).orElseThrow().getStatus());
            assertEquals(HttpStatus.OK, requestAt(port, "POST", refundPath, admin, refundBody).getStatusCode());
            assertEquals(HttpStatus.BAD_REQUEST, requestAt(peerApplication.port(), "POST", releasePath, admin, Map.of()).getStatusCode());
            assertEquals(expected, payoutRepository.findById(payout.getPayoutId()).orElseThrow().getStatus());
            assertEquals(transferred ? 1 : 0, PAYOUT_SUBMIT_CALLS.get());
            assertEquals(transferred ? 1 : 0, PAYOUTS.size());
            assertEquals(transferred ? 1 : 0, jdbc.queryForObject(
                    "select count(*) from seller_payout_transfers where payout_id=?", Integer.class, payout.getPayoutId()));
            if (transferred) {
                assertEquals("SUCCEEDED", jdbc.queryForObject(
                        "select status from seller_payout_transfers where payout_id=?", String.class, payout.getPayoutId()));
                assertEquals(PAYOUTS.get("seller-payout:" + payout.getPayoutId()).get("payoutId"), jdbc.queryForObject(
                        "select provider_transfer_id from seller_payout_transfers where payout_id=?", String.class, payout.getPayoutId()));
            }
            var stored = orderRepository.findById(orderId).orElseThrow();
            assertNotNull(stored.getShippedAt());
            assertNotNull(stored.getBuyerConfirmedAt());
            assertEquals("REFUNDED", payments.findById(stored.getPaymentId()).orElseThrow().getStatus());
            assertEquals("REFUNDED", jdbc.queryForObject("select status from orders where order_id=?", String.class, stored.getOrderId()));
            assertEquals("SUCCEEDED", jdbc.queryForObject("select status from refunds where payment_id=?", String.class, stored.getPaymentId()));
            assertEquals("ADMIN", jdbc.queryForObject("select source_type from refunds where payment_id=?", String.class, stored.getPaymentId()));
            assertEquals(1, jdbc.queryForObject("select count(*) from provider_cancellations where payment_id=?", Integer.class, stored.getPaymentId()));
            assertEquals(1, jdbc.queryForObject(
                    "select count(*) from outbox_events where aggregate_id=? and event_type='PAYMENT_REFUND_OBSERVED'", Integer.class, stored.getPaymentId()));
            assertEquals(1, TOSS_CONFIRM_CALLS.get());
            assertEquals(1, TOSS_CANCEL_CALLS.get());
            assertEquals(0, inventoryRepository.findById(productId).orElseThrow().getAvailableQuantity());
            assertEquals(0, Integer.parseInt(String.valueOf(resourceReservationService.getResourceStatus("inventory:" + productId).get("available"))));
            assertTrue(movementRepository.findByOperationKey("order-cancel-restore-" + orderId).isEmpty(),
                    "Shipped goods must not become resellable after an admin refund");
        } finally {
            barrier.releaseResponse.countDown();
            PAYOUT_RESPONSE_BARRIER.compareAndSet(barrier, null);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    private boolean isPayoutRecovery(CheckoutFault fault) {
        return fault == CheckoutFault.PAYOUT_RESPONSE_LOSS || fault == CheckoutFault.PAYOUT_PROCESS_RESTART
                || fault == CheckoutFault.PAYOUT_RESTART_REFUND;
    }

    private void recoverCommittedPayout(CheckoutFault fault, HttpHeaders admin, String orderId, String productId,
                                        String payoutId, String releasePath, String refundPath, Map<String, Object> refundBody) throws Exception {
        PAYOUT_LOOKUP_HTTP_STATUS.set(401);
        var barrier = new CancelResponseBarrier();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        boolean refundDuringOutage = fault == CheckoutFault.PAYOUT_RESTART_REFUND;
        int recoveryPort;
        try {
            if (fault == CheckoutFault.PAYOUT_RESPONSE_LOSS) {
                DROP_PAYOUT_RESPONSE.set(true);
                var response = requestAt(port, "POST", releasePath, admin, Map.of());
                assertTrue(response.getStatusCode().is5xxServerError(), response.getBody());
                assertEquals("UNKNOWN", jdbc.queryForObject("select status from seller_payout_transfers where payout_id=?", String.class, payoutId));
                peerApplication = startChildApplication(false, true);
                assertTrue(peerApplication.pid() != childApplication.pid());
                recoveryPort = peerApplication.port();
            } else {
                PAYOUT_RESPONSE_BARRIER.set(barrier);
                int originalServerPort = port;
                var response = executor.submit(() -> requestAt(originalServerPort, "POST", releasePath, admin, Map.of()));
                assertTrue(barrier.providerCommitted.await(30, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals("PROCESSING", jdbc.queryForObject("select status from seller_payout_transfers where payout_id=?", String.class, payoutId));
                long originalPid = childApplication.pid();
                childApplication.close();
                assertFalse(childApplication.isAlive());
                barrier.releaseResponse.countDown();
                try {
                    assertTrue(response.get(15, java.util.concurrent.TimeUnit.SECONDS).getStatusCode().is5xxServerError());
                } catch (java.util.concurrent.ExecutionException failure) {
                    assertTrue(failure.getCause() instanceof java.io.IOException, "Expected lost app connection: " + failure.getCause());
                }
                assertEquals("PROCESSING", jdbc.queryForObject("select status from seller_payout_transfers where payout_id=?", String.class, payoutId));
                if (refundDuringOutage) {
                    peerApplication = startChildApplication(true);
                    var refund = requestAt(peerApplication.port(), "POST", refundPath, admin, refundBody);
                    assertEquals(HttpStatus.OK, refund.getStatusCode(), refund.getBody());
                    awaitPayoutRecoveryCondition(() -> orderRepository.findById(orderId).orElseThrow().getStatus() == MarketplaceOrderStatus.REFUNDED,
                            "Refund did not finalize during payout outage");
                    assertEquals(SellerPayoutStatus.CANCELLED, payoutRepository.findById(payoutId).orElseThrow().getStatus());
                }
                childApplication = startChildApplication(false, true);
                assertTrue(originalPid != childApplication.pid());
                port = childApplication.port();
                recoveryPort = port;
            }

            // Only the scheduled worker drives recovery; no release retry or direct coordinator invocation.
            awaitPayoutRecoveryCondition(() -> PAYOUT_LOOKUP_CALLS.get() >= 2
                            && "UNKNOWN".equals(jdbc.queryForObject("select status from seller_payout_transfers where payout_id=?", String.class, payoutId)),
                    "401 lookup must remain recoverable rather than terminal FAILED");
            assertEquals(refundDuringOutage ? SellerPayoutStatus.CANCELLED : SellerPayoutStatus.READY_FOR_RELEASE,
                    payoutRepository.findById(payoutId).orElseThrow().getStatus());
            int priorLookups = PAYOUT_LOOKUP_CALLS.get();
            PAYOUT_LOOKUP_HTTP_STATUS.set(503);
            awaitPayoutRecoveryCondition(() -> PAYOUT_LOOKUP_CALLS.get() > priorLookups
                            && "PAYOUT_LOOKUP_HTTP_503".equals(jdbc.queryForObject("select failure_reason from seller_payout_transfers where payout_id=?", String.class, payoutId)),
                    "503 lookup did not leave a durable unknown observation");
            assertEquals(1, PAYOUT_SUBMIT_CALLS.get());
            for (Map<String, Object> mismatch : java.util.List.<Map<String, Object>>of(
                    Map.of("amount", 1), Map.of("currency", "USD"), Map.of("refPayoutId", "unrelated-payout"))) {
                PAYOUT_LOOKUP_HTTP_STATUS.set(503);
                awaitPayoutRecoveryCondition(() -> "PAYOUT_LOOKUP_HTTP_503".equals(jdbc.queryForObject(
                                "select failure_reason from seller_payout_transfers where payout_id=?", String.class, payoutId)),
                        "Expected durable lookup error before injecting the next receipt");
                PAYOUT_LOOKUP_OVERRIDES.set(mismatch);
                PAYOUT_LOOKUP_HTTP_STATUS.set(0);
                String reason = mismatch.containsKey("refPayoutId") ? "PAYOUT_NOT_FOUND_YET" : "PAYOUT_RESPONSE_MISMATCH";
                awaitPayoutRecoveryCondition(() -> reason.equals(jdbc.queryForObject(
                                "select failure_reason from seller_payout_transfers where payout_id=?", String.class, payoutId)),
                        "Unmatched payout receipt must remain recoverable");
                assertEquals("UNKNOWN", jdbc.queryForObject("select status from seller_payout_transfers where payout_id=?", String.class, payoutId));
                assertNull(jdbc.queryForObject("select provider_transfer_id from seller_payout_transfers where payout_id=?", String.class, payoutId));
                assertEquals(refundDuringOutage ? SellerPayoutStatus.CANCELLED : SellerPayoutStatus.READY_FOR_RELEASE,
                        payoutRepository.findById(payoutId).orElseThrow().getStatus());
                assertEquals(1, PAYOUT_SUBMIT_CALLS.get());
            }
            PAYOUT_LOOKUP_OVERRIDES.set(Map.of());
            PAYOUT_LOOKUP_HTTP_STATUS.set(0);
            var expected = refundDuringOutage ? SellerPayoutStatus.RECOVERY_REQUIRED : SellerPayoutStatus.RELEASED;
            awaitPayoutRecoveryCondition(() -> payoutRepository.findById(payoutId).orElseThrow().getStatus() == expected,
                    "Scheduled lookup did not recover the committed payout");
            assertEquals("SUCCEEDED", jdbc.queryForObject("select status from seller_payout_transfers where payout_id=?", String.class, payoutId));
            assertEquals(PAYOUTS.get("seller-payout:" + payoutId).get("payoutId"), jdbc.queryForObject(
                    "select provider_transfer_id from seller_payout_transfers where payout_id=?", String.class, payoutId));
            assertEquals(1, jdbc.queryForObject("select count(*) from seller_payout_transfers where payout_id=?", Integer.class, payoutId));
            assertEquals(1, PAYOUTS.size());
            assertEquals(refundDuringOutage ? HttpStatus.BAD_REQUEST : HttpStatus.OK,
                    requestAt(recoveryPort, "POST", releasePath, admin, Map.of()).getStatusCode());
            assertEquals(1, PAYOUT_SUBMIT_CALLS.get());
            assertEquals(1, TOSS_CONFIRM_CALLS.get());
            assertEquals(refundDuringOutage ? 1 : 0, TOSS_CANCEL_CALLS.get());
            var stored = orderRepository.findById(orderId).orElseThrow();
            assertEquals(refundDuringOutage ? MarketplaceOrderStatus.REFUNDED : MarketplaceOrderStatus.PAID, stored.getStatus());
            assertEquals(refundDuringOutage ? "REFUNDED" : "APPROVED", payments.findById(stored.getPaymentId()).orElseThrow().getStatus());
            assertNotNull(stored.getShippedAt());
            assertNotNull(stored.getBuyerConfirmedAt());
            assertEquals(0, inventoryRepository.findById(productId).orElseThrow().getAvailableQuantity());
            assertEquals(0, Integer.parseInt(String.valueOf(resourceReservationService.getResourceStatus("inventory:" + productId).get("available"))));
            assertTrue(movementRepository.findByOperationKey("order-cancel-restore-" + orderId).isEmpty());
        } finally {
            barrier.releaseResponse.countDown();
            PAYOUT_RESPONSE_BARRIER.compareAndSet(barrier, null);
            PAYOUT_LOOKUP_HTTP_STATUS.set(0);
            PAYOUT_LOOKUP_OVERRIDES.set(Map.of());
            DROP_PAYOUT_RESPONSE.set(false);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    private void awaitPayoutRecoveryCondition(java.util.function.BooleanSupplier condition, String message) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(25).toNanos();
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, message);
            Thread.sleep(100);
        }
    }

    private void raceCancellationAndShipment(CheckoutFault fault, HttpHeaders buyer, HttpHeaders seller,
                                             HttpHeaders admin, String sellerId, String orderId, String productId) throws Exception {
        peerApplication = startChildApplication(false);
        assertTrue(peerApplication.pid() != childApplication.pid());
        String cancellationPath = "/api/c2c/orders/" + orderId + "/cancellations";
        String fulfillmentPath = "/api/sellers/" + sellerId + "/orders/" + orderId + "/fulfillment";
        var payout = payoutRepository.findBySourceTypeAndSourceId("MARKETPLACE_ORDER", orderId).orElseThrow();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(3);
        java.util.concurrent.Future<ResponseEntity<String>> cancellation;
        java.util.concurrent.Future<ResponseEntity<String>> shipment;
        try {
            java.util.concurrent.Callable<ResponseEntity<String>> cancelCall = () -> requestAt(childApplication.port(),
                    "POST", cancellationPath, buyer, Map.of("reason", "Concurrent buyer cancellation"));
            java.util.concurrent.Callable<ResponseEntity<String>> shipCall = () -> requestAt(peerApplication.port(),
                    "PATCH", fulfillmentPath, seller, Map.of("fulfillmentStatus", "SHIPPED",
                            "trackingCarrier", "E2E", "trackingNumber", "TRACK-" + orderId));
            // Hold the row only in this fixture, to prove both independent servers contend on PostgreSQL.
            try (var lock = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
                lock.setAutoCommit(false);
                try (var statement = lock.prepareStatement("select marketplace_order_id from marketplace_orders where marketplace_order_id=? for update")) {
                    statement.setString(1, orderId);
                    statement.executeQuery().close();
                }
                if (fault == CheckoutFault.CANCEL_FIRST) {
                    cancellation = executor.submit(cancelCall);
                    awaitBlockedApplicationSessions(1);
                    shipment = executor.submit(shipCall);
                } else {
                    shipment = executor.submit(shipCall);
                    awaitBlockedApplicationSessions(1);
                    cancellation = executor.submit(cancelCall);
                }
                awaitBlockedApplicationSessions(2);
                var release = executor.submit(() -> requestAt(peerApplication.port(), "POST",
                        "/api/sellers/" + sellerId + "/payouts/" + payout.getPayoutId() + "/release", admin, Map.of()));
                assertEquals(HttpStatus.BAD_REQUEST, release.get(15, java.util.concurrent.TimeUnit.SECONDS).getStatusCode(),
                        "HELD payout must not be released during the race");
                lock.commit();
            }
            var cancelResponse = cancellation.get(30, java.util.concurrent.TimeUnit.SECONDS);
            var shipResponse = shipment.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(0, jdbc.queryForObject(
                    "select count(*) from seller_payout_transfers where payout_id=?", Integer.class, payout.getPayoutId()));
            if (fault == CheckoutFault.CANCEL_FIRST) {
                assertEquals(HttpStatus.OK, cancelResponse.getStatusCode(), cancelResponse.getBody());
                assertEquals(HttpStatus.CONFLICT, shipResponse.getStatusCode(), shipResponse.getBody());
                assertTrue(shipResponse.getBody().contains("REFUND_VERIFICATION_REQUIRED"));
                assertNull(orderRepository.findById(orderId).orElseThrow().getShippedAt());
                var a = executor.submit(cancelCall);
                var b = executor.submit(() -> requestAt(peerApplication.port(), "POST", cancellationPath, buyer, Map.of("reason", "Replay")));
                assertEquals(HttpStatus.OK, a.get(15, java.util.concurrent.TimeUnit.SECONDS).getStatusCode());
                assertEquals(HttpStatus.OK, b.get(15, java.util.concurrent.TimeUnit.SECONDS).getStatusCode());
                assertEquals(1, TOSS_CANCEL_CALLS.get());
                assertEquals(1, jdbc.queryForObject("select count(*) from inventory_movements where operation_key=?",
                        Integer.class, "order-cancel-restore-" + orderId));
            } else {
                assertEquals(HttpStatus.OK, shipResponse.getStatusCode(), shipResponse.getBody());
                assertEquals(HttpStatus.CONFLICT, cancelResponse.getStatusCode(), cancelResponse.getBody());
                assertEquals(HttpStatus.BAD_REQUEST, requestAt(childApplication.port(), "PATCH", fulfillmentPath,
                        seller, Map.of("fulfillmentStatus", "PROCESSING")).getStatusCode());
                assertEquals(HttpStatus.CONFLICT, cancelCall.call().getStatusCode());
                var stored = orderRepository.findById(orderId).orElseThrow();
                assertEquals("SHIPPED", stored.getFulfillmentStatus().name());
                assertNotNull(stored.getShippedAt());
                assertEquals(MarketplaceOrderStatus.PAID, stored.getStatus());
                assertEquals("APPROVED", payments.findById(stored.getPaymentId()).orElseThrow().getStatus());
                assertEquals(0, jdbc.queryForObject("select count(*) from refunds where payment_id=?", Integer.class, stored.getPaymentId()));
                assertEquals(0, jdbc.queryForObject("select count(*) from marketplace_order_cancellations where marketplace_order_id=?", Integer.class, orderId));
                assertEquals(0, TOSS_CANCEL_CALLS.get());
                assertEquals(SellerPayoutStatus.HELD, payoutRepository.findById(payout.getPayoutId()).orElseThrow().getStatus());
                assertEquals(0, inventoryRepository.findById(productId).orElseThrow().getAvailableQuantity());
                assertEquals(0, Integer.parseInt(String.valueOf(resourceReservationService
                        .getResourceStatus("inventory:" + productId).get("available"))));
                assertTrue(movementRepository.findByOperationKey("order-cancel-restore-" + orderId).isEmpty());
            }
            assertEquals(1, TOSS_CONFIRM_CALLS.get());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    private void awaitBlockedApplicationSessions(int count) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos();
        while (jdbc.queryForObject("select count(distinct application_name) from pg_stat_activity where application_name like 'recovery-app-%' and wait_event_type='Lock'",
                Integer.class) < count) {
            assertTrue(System.nanoTime() < deadline, "Expected independent app sessions waiting on the order lock");
            Thread.sleep(100);
        }
    }

    private ResponseEntity<String> requestAt(int serverPort, String method, String path, HttpHeaders headers,
                                             Map<String, Object> body) throws Exception {
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + serverPort + path))
                .timeout(java.time.Duration.ofSeconds(45)).header("Content-Type", "application/json")
                .method(method, java.net.http.HttpRequest.BodyPublishers.ofString(new tools.jackson.databind.ObjectMapper().writeValueAsString(body)));
        headers.forEach((name, values) -> values.forEach(value -> request.header(name, value)));
        var response = java.net.http.HttpClient.newHttpClient().send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        return ResponseEntity.status(response.statusCode()).body(response.body());
    }

    private void restartDuringRefund(HttpHeaders buyerHeaders, String orderId, String paymentId,
                                     String productId, String paymentKey) throws Exception {
        var barrier = new CancelResponseBarrier();
        CANCEL_RESPONSE_BARRIER.set(barrier);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            // The HTTP caller must lose its connection when the app is killed mid-refund.
            var request = executor.submit(() -> post("/api/c2c/orders/" + orderId + "/cancellations",
                    buyerHeaders, Map.of("reason", "Refund process crash drill"), String.class));
            assertTrue(barrier.providerCommitted.await(30, java.util.concurrent.TimeUnit.SECONDS),
                    "Provider did not commit the refund before the crash");
            long oldPid = childApplication.pid();
            childApplication.close();
            assertFalse(childApplication.isAlive());
            barrier.releaseResponse.countDown();
            try {
                var response = request.get(30, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(response.getStatusCode().is5xxServerError(),
                        "Killed app must not acknowledge a completed cancellation");
            } catch (java.util.concurrent.ExecutionException failure) {
                assertTrue(failure.getCause() instanceof org.springframework.web.client.ResourceAccessException,
                        "Expected lost HTTP connection, not another request failure: " + failure.getCause());
            }
            assertEquals("CANCELED", TOSS_PAYMENTS.get(paymentKey).get("status"));
            assertEquals(1, TOSS_CANCEL_CALLS.get());
            assertEquals("PROCESSING", jdbc.queryForObject(
                    "select status from refunds where payment_id=?", String.class, paymentId));
            assertEquals("REFUND_PROCESSING", jdbc.queryForObject(
                    "select status from marketplace_order_cancellations where marketplace_order_id=?", String.class, orderId));
            assertRefundInventoryProtected(orderId, productId);

            childApplication = startChildApplication(true);
            port = childApplication.port();
            assertTrue(childApplication.isAlive());
            assertTrue(oldPid != childApplication.pid());
            // Provider lookups still fail: even a new app must not release stock or reissue cancel.
            long lookupDeadline = System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos();
            while (!"UNKNOWN".equals(jdbc.queryForObject(
                    "select status from refunds where payment_id=?", String.class, paymentId))) {
                assertTrue(System.nanoTime() < lookupDeadline, "Restarted app did not attempt refund lookup");
                Thread.sleep(250);
            }
            assertRefundInventoryProtected(orderId, productId);
            assertEquals(1, TOSS_CANCEL_CALLS.get());
            FAIL_LOOKUPS.set(0);

            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(45).toNanos();
            while (!"COMPLETED".equals(jdbc.queryForObject(
                    "select status from marketplace_order_cancellations where marketplace_order_id=?", String.class, orderId))) {
                assertTrue(System.nanoTime() < deadline, "Restarted app did not reconcile the persisted refund");
                Thread.sleep(250);
            }
            assertEquals("REFUNDED", payments.findById(paymentId).orElseThrow().getStatus());
            assertEquals("SUCCEEDED", jdbc.queryForObject(
                    "select status from refunds where payment_id=?", String.class, paymentId));
            assertEquals(1, jdbc.queryForObject(
                    "select count(*) from provider_cancellations where payment_id=?", Integer.class, paymentId));
            assertEquals(1, jdbc.queryForObject(
                    "select count(*) from inventory_movements where operation_key=?", Integer.class, "order-cancel-restore-" + orderId));
            assertEquals(1, TOSS_CANCEL_CALLS.get());
            assertEquals(1, TOSS_CONFIRM_CALLS.get());
        } finally {
            barrier.releaseResponse.countDown();
            CANCEL_RESPONSE_BARRIER.compareAndSet(barrier, null);
            FAIL_LOOKUPS.set(0);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    private void assertRefundInventoryProtected(String orderId, String productId) {
        assertEquals(0, inventoryRepository.findById(productId).orElseThrow().getAvailableQuantity());
        assertEquals(0, Integer.parseInt(String.valueOf(resourceReservationService
                .getResourceStatus("inventory:" + productId).get("available"))));
        assertEquals(SellerPayoutStatus.HELD, payoutRepository
                .findBySourceTypeAndSourceId("MARKETPLACE_ORDER", orderId).orElseThrow().getStatus());
        assertTrue(movementRepository.findByOperationKey("order-cancel-restore-" + orderId).isEmpty());
        assertFalse("COMPLETED".equals(jdbc.queryForObject(
                "select status from marketplace_order_cancellations where marketplace_order_id=?", String.class, orderId)));
    }

    private ListingMediaResponse[] uploadMedia(String listingId,
                                                HttpHeaders identityHeaders,
                                                String mediaType,
                                                String filename,
                                                byte[] content) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("mediaType", mediaType);
        body.add("files", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(identityHeaders);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<ListingMediaResponse[]> response = restTemplate.exchange(
                url("/api/c2c/listings/" + listingId + "/media"),
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                ListingMediaResponse[].class);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(),
                () -> "Unexpected media upload response: " + response.getBody());
        return response.getBody();
    }

    private void verifyEarlyOrderFailure(CheckoutFault fault, HttpHeaders buyerHeaders,
            TossPaymentIntentResponse intent, Map<String, Object> confirmBody, String productId) throws Exception {
        var stored = intents.findById(intent.getIntentId()).orElseThrow();
        String reservationId = stored.getInventoryReservationId();
        var command = com.example.payment.application.temporal.ReservationWorkflowCommand.builder()
                .workflowId("closure-test-" + intent.getIntentId()).reservationId(reservationId)
                .orderId(intent.getOrderId()).paymentId("PAY-" + UUID.randomUUID())
                .productId(productId).customerId(stored.getCustomerId()).quantity(1)
                .amount(intent.getAmount()).unitPrice(intent.getAmount()).priceSource("SERVER_PRODUCT_PRICE")
                .priceCalculatedAt(java.time.LocalDateTime.now()).currency("KRW").paymentMethod("CREDIT_CARD")
                .tossIntentId(intent.getIntentId()).tossOrderId(intent.getOrderId())
                .tossPaymentKey(String.valueOf(confirmBody.get("paymentKey"))).build();
        assertEquals("NOT_STARTED", jdbc.queryForObject(
                "select authorization_state from inventory_reservations where reservation_id=?", String.class, reservationId));
        if (fault == CheckoutFault.AUTHORIZATION_RACE) {
            // The missing order FK must roll back both the payment insert and authorization claim.
            org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                    () -> paymentWriter.begin(command));
            assertEquals("NOT_STARTED", jdbc.queryForObject(
                    "select authorization_state from inventory_reservations where reservation_id=?", String.class, reservationId));
            assertTrue(payments.findByReservationId(reservationId).isEmpty());
            assertTrue(activities.createOrder(command).isSuccess());
            var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
            var start = new java.util.concurrent.CountDownLatch(1);
            try {
                var claim = executor.submit(() -> {
                    start.await();
                    try { return paymentWriter.begin(command).authorize(); }
                    catch (IllegalStateException closed) { return false; }
                });
                var release = executor.submit(() -> {
                    start.await();
                    try { activities.cancelReservation(command, "competing compensation"); return true; }
                    catch (IllegalStateException protectedPayment) { return false; }
                });
                start.countDown();
                boolean claimed = claim.get(20, java.util.concurrent.TimeUnit.SECONDS);
                boolean released = release.get(20, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(claimed ^ released, "Exactly one of authorization claim and closure may commit");
                assertEquals(claimed ? "STARTED" : "CLOSED_WITHOUT_AUTHORIZATION", jdbc.queryForObject(
                        "select authorization_state from inventory_reservations where reservation_id=?", String.class, reservationId));
                assertEquals(released ? 1 : 0, inventoryRepository.findById(productId).orElseThrow().getAvailableQuantity());
                assertFalse(holds.release(reservationId, "EXPIRED"));
                if (claimed) {
                    assertEquals("PROCESSING", payments.findByReservationId(reservationId).orElseThrow().getStatus());
                    assertFalse(paymentWriter.begin(command).authorize());
                    org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> holds.compensate(command));
                } else {
                    assertTrue(payments.findByReservationId(reservationId).isEmpty());
                    assertEquals("CANCELLED", jdbc.queryForObject("select status from orders where order_id=?",
                            String.class, command.getOrderId()));
                    org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> paymentWriter.begin(command));
                }
                assertEquals(0, TOSS_CONFIRM_CALLS.get());
                assertEquals(0, TOSS_CANCEL_CALLS.get());
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS));
            }
            return;
        }
        if (fault == CheckoutFault.CLOSURE_ROLLBACK) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
                    new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                        assertTrue(holds.compensate(command));
                        throw new IllegalStateException("Rollback after proof, inventory and outbox writes");
                    }));
            assertEquals("NOT_STARTED", jdbc.queryForObject(
                    "select authorization_state from inventory_reservations where reservation_id=?", String.class, reservationId));
            assertEquals("RESERVED", jdbc.queryForObject(
                    "select status from inventory_reservations where reservation_id=?", String.class, reservationId));
            assertEquals(0, jdbc.queryForObject("select count(*) from outbox_events where aggregate_id=? and event_type='AUTHORIZATION_CLOSED_WITHOUT_SUBMISSION'",
                    Integer.class, reservationId));
            assertEquals(0, inventoryRepository.findById(productId).orElseThrow().getAvailableQuantity());
            assertEquals(1, Integer.parseInt(String.valueOf(resourceReservationService
                    .getResourceStatus("inventory:" + productId).get("reserved"))));
        }
        failOrderCreation = true;
        post("/api/payments/toss/confirm", buyerHeaders, confirmBody, String.class);
        var terminal = awaitTerminalStatus(buyerHeaders, intent.getIntentId());
        assertEquals("FAILED", terminal.getStatus());
        assertTrue(orderCreationFailures.get() > 0);
        assertEquals(0, TOSS_CONFIRM_CALLS.get());
        assertEquals(0, TOSS_CANCEL_CALLS.get());
        assertTrue(payments.findByReservationId(reservationId).isEmpty());
        assertEquals(0, jdbc.queryForObject("select count(*) from orders where order_id=?", Integer.class, intent.getOrderId()));
        assertEquals("CLOSED_WITHOUT_AUTHORIZATION", jdbc.queryForObject(
                "select authorization_state from inventory_reservations where reservation_id=?", String.class, reservationId));
        assertEquals(terminal.getWorkflowId(), jdbc.queryForObject(
                "select authorization_close_workflow_id from inventory_reservations where reservation_id=?", String.class, reservationId));
        assertEquals("CANCELLED", jdbc.queryForObject(
                "select status from inventory_reservations where reservation_id=?", String.class, reservationId));
        failOrderCreation = false;
        assertFalse(activities.createOrder(command).isSuccess(), "Late activity must not recreate an order");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> activities.processPayment(command));
        activities.cancelReservation(command, "duplicate activity");
        assertFalse(holds.release(reservationId, "EXPIRED"));
        assertEquals(0, TOSS_CONFIRM_CALLS.get());
        assertEquals(1, inventoryRepository.findById(productId).orElseThrow().getAvailableQuantity());
        assertEquals(0, inventoryRepository.findById(productId).orElseThrow().getReservedQuantity());
        assertEquals(0, Integer.parseInt(String.valueOf(resourceReservationService
                .getResourceStatus("inventory:" + productId).get("reserved"))));
        for (String event : java.util.List.of("AUTHORIZATION_CLOSED_WITHOUT_SUBMISSION", "RESERVATION_CANCELLED")) {
            assertEquals(1, jdbc.queryForObject("select count(*) from outbox_events where aggregate_id=? and event_type=?",
                    Integer.class, reservationId, event));
        }
        assertEquals("FAILED", awaitTerminalStatus(buyerHeaders, intent.getIntentId()).getStatus());
        verifyLegacyReleaseDoesNotRestockAgain(reservationId, productId);
    }

    @SuppressWarnings("unchecked")
    private void verifyLegacyReleaseDoesNotRestockAgain(String reservationId, String productId) {
        var issues = applicationContext.getBean(com.example.payment.domain.repository.InventorySyncIssueRepository.class);
        var job = new com.example.payment.scheduler.InventoryReconciliationJob(inventoryRepository,
                resourceReservationService,
                applicationContext.getBean(com.example.payment.domain.repository.InventoryReservationRecordRepository.class),
                transactionManager, (org.springframework.data.redis.core.RedisTemplate<String, Object>) applicationContext.getBean("redisTemplate"),
                issues, applicationContext.getBean(com.example.payment.application.service.InventorySyncIssueService.class),
                holds, applicationContext.getBean(com.example.payment.application.service.InventoryReleasePolicy.class));
        for (String status : java.util.List.of("CANCELLED", "CANCELED", "EXPIRED", "RELEASED")) {
            jdbc.update("update inventory_reservations set status=? where reservation_id=?", status, reservationId);
            var issue = issues.saveAndFlush(com.example.payment.domain.entity.InventorySyncIssue.builder()
                    .issueId("LEGACY-" + UUID.randomUUID()).reservationId(reservationId).productId(productId)
                    .quantity(1).issueType("RELEASE_DB_APPLY_REQUIRED").status("PENDING").attemptCount(0).build());
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(job, "applyDatabaseRepair", issue);
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(job, "applyDatabaseRepair", issue);
            assertEquals("RESOLVED", issues.findById(issue.getIssueId()).orElseThrow().getStatus());
            assertEquals(1, inventoryRepository.findById(productId).orElseThrow().getAvailableQuantity());
            assertEquals(0, inventoryRepository.findById(productId).orElseThrow().getReservedQuantity());
        }
    }

    private CompleteReservationResponse awaitTerminalConfirm(
            HttpHeaders headers, Map<String, Object> body) throws InterruptedException {
        CompleteReservationResponse response = null;
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline) {
            ResponseEntity<CompleteReservationResponse> current = post(
                    "/api/payments/toss/confirm", headers, body, CompleteReservationResponse.class);
            response = current.getBody();
            if (response != null && !"PENDING".equals(response.getStatus())) {
                assertEquals(HttpStatus.OK, current.getStatusCode());
                return response;
            }
            Thread.sleep(500L);
        }
        throw new AssertionError("Temporal checkout did not reach a terminal state: "
                + (response != null ? response.getWorkflowId() : "no response"));
    }

    private CompleteReservationResponse awaitTerminalStatus(HttpHeaders headers, String intentId) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline) {
            var response = get("/api/payments/toss/intents/" + intentId, headers,
                    CompleteReservationResponse.class, HttpStatus.OK).getBody();
            assertNotNull(response);
            if (!"PENDING".equals(response.getStatus())) return response;
            Thread.sleep(500);
        }
        throw new AssertionError("Restarted app did not recover intent through status lookup: " + intentId);
    }

    private Map<String, Object> genericIntentBody(String runId, String productId) {
        return Map.of(
                "productId", productId,
                "quantity", 1,
                "idempotencyKey", "E2E-GENERIC-" + runId,
                "clientId", "commerce-e2e",
                "paymentInfo", Map.of(
                        "amount", 25000,
                        "currency", "KRW",
                        "paymentMethod", "CREDIT_CARD"
                )
        );
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

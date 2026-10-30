package com.example.payment;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

abstract class FullCommerceTestcontainersSupport extends TestcontainersIntegrationSupport {

    private static final Network COMMERCE_NETWORK = Network.newNetwork();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.5.0"))
            .withNetwork(COMMERCE_NETWORK);

    static final PostgreSQLContainer<?> TEMPORAL_POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("temporal")
                    .withUsername("temporal")
                    .withPassword("temporal-test-only")
                    .withNetwork(COMMERCE_NETWORK)
                    .withNetworkAliases("temporal-postgres");

    static final GenericContainer<?> TEMPORAL = new GenericContainer<>(
            DockerImageName.parse("temporalio/auto-setup:1.25"))
            .withExposedPorts(7233)
            .withNetwork(COMMERCE_NETWORK)
            .withEnv("DB", "postgres12")
            .withEnv("DB_PORT", "5432")
            .withEnv("POSTGRES_USER", "temporal")
            .withEnv("POSTGRES_PWD", "temporal-test-only")
            .withEnv("POSTGRES_SEEDS", "temporal-postgres")
            .withEnv("DEFAULT_NAMESPACE", "payment")
            .dependsOn(TEMPORAL_POSTGRES)
            .waitingFor(Wait.forListeningPort())
            .withStartupTimeout(Duration.ofMinutes(3));

    static final AtomicInteger TOSS_CONFIRM_CALLS = new AtomicInteger();
    static final AtomicInteger TOSS_CANCEL_CALLS = new AtomicInteger();
    static final AtomicInteger PAYOUT_SUBMIT_CALLS = new AtomicInteger();
    static final AtomicInteger PAYOUT_LOOKUP_CALLS = new AtomicInteger();
    static final AtomicInteger PAYOUT_LOOKUP_HTTP_STATUS = new AtomicInteger();
    static final java.util.concurrent.atomic.AtomicReference<Map<String, Object>> PAYOUT_LOOKUP_OVERRIDES =
            new java.util.concurrent.atomic.AtomicReference<>(Map.of());
    static final java.util.concurrent.atomic.AtomicBoolean DROP_PAYOUT_RESPONSE = new java.util.concurrent.atomic.AtomicBoolean();
    static final java.util.concurrent.ConcurrentMap<String, Map<String, Object>> PAYOUTS = new java.util.concurrent.ConcurrentHashMap<>();
    static final java.util.concurrent.atomic.AtomicReference<CancelResponseBarrier> PAYOUT_RESPONSE_BARRIER =
            new java.util.concurrent.atomic.AtomicReference<>();

    private static HttpServer tossStub;

    @DynamicPropertySource
    static void registerCommerceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", () -> {
            ensureCommerceContainersStarted();
            return KAFKA.getBootstrapServers();
        });
        registry.add("app.temporal.target", () -> {
            ensureCommerceContainersStarted();
            return TEMPORAL.getHost() + ":" + TEMPORAL.getMappedPort(7233);
        });
        registry.add("payment.toss.base-url", FullCommerceTestcontainersSupport::tossBaseUrl);
        registry.add("payment.toss.client-key", () -> "test_ck_commerce_e2e");
        registry.add("payment.toss.secret-key", () -> "test_sk_commerce_e2e");
        registry.add("payment.toss.mode", () -> "test");
        registry.add("payment.default-gateway", () -> "TOSS_PAYMENTS");
        registry.add("payment.allow-gateway-fallback", () -> "false");
        registry.add("payment.mock.enabled", () -> "false");
        registry.add("app.temporal.enabled", () -> "true");
        registry.add("app.temporal.worker-enabled", () -> "true");
        registry.add("app.outbox.enabled", () -> "true");
        registry.add("app.checkout.public-complete-enabled", () -> "false");
        registry.add("app.checkout.legacy-reservation-api-enabled", () -> "false");
        registry.add("app.marketplace.realtime.redis-broadcast-enabled", () -> "false");
    }

    static final java.util.concurrent.ConcurrentMap<String, Map<String, Object>> TOSS_PAYMENTS = new java.util.concurrent.ConcurrentHashMap<>();
    static final java.util.Set<String> TOSS_REFUND_KEYS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    static final java.util.concurrent.atomic.AtomicBoolean DROP_CONFIRM_RESPONSE = new java.util.concurrent.atomic.AtomicBoolean();
    static final java.util.concurrent.atomic.AtomicBoolean DROP_CANCEL_RESPONSE = new java.util.concurrent.atomic.AtomicBoolean();
    static final java.util.concurrent.atomic.AtomicBoolean FAIL_CANCEL = new java.util.concurrent.atomic.AtomicBoolean();
    static final java.util.concurrent.atomic.AtomicInteger FAIL_LOOKUPS = new java.util.concurrent.atomic.AtomicInteger();
    static final java.util.concurrent.atomic.AtomicReference<CancelResponseBarrier> CANCEL_RESPONSE_BARRIER =
            new java.util.concurrent.atomic.AtomicReference<>();

    static final class CancelResponseBarrier {
        final java.util.concurrent.CountDownLatch providerCommitted = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch releaseResponse = new java.util.concurrent.CountDownLatch(1);
    }

    static void resetTossStub() {
        var payoutBarrier = PAYOUT_RESPONSE_BARRIER.getAndSet(null);
        if (payoutBarrier != null) payoutBarrier.releaseResponse.countDown();
        PAYOUT_SUBMIT_CALLS.set(0);
        PAYOUT_LOOKUP_CALLS.set(0);
        PAYOUT_LOOKUP_HTTP_STATUS.set(0);
        PAYOUT_LOOKUP_OVERRIDES.set(Map.of());
        DROP_PAYOUT_RESPONSE.set(false);
        PAYOUTS.clear();
        var barrier = CANCEL_RESPONSE_BARRIER.getAndSet(null);
        if (barrier != null) barrier.releaseResponse.countDown();
        TOSS_PAYMENTS.clear();
        TOSS_REFUND_KEYS.clear();
        DROP_CONFIRM_RESPONSE.set(false);
        DROP_CANCEL_RESPONSE.set(false);
        FAIL_CANCEL.set(false);
        FAIL_LOOKUPS.set(0);
        TOSS_CONFIRM_CALLS.set(0);
        TOSS_CANCEL_CALLS.set(0);
    }

    static synchronized void stopTossStub() {
        if (tossStub != null) {
            tossStub.stop(0);
            tossStub = null;
        }
    }

    private static synchronized void ensureCommerceContainersStarted() {
        if (!KAFKA.isRunning() || !TEMPORAL_POSTGRES.isRunning()) {
            Startables.deepStart(Stream.of(KAFKA, TEMPORAL_POSTGRES)).join();
        }
        if (!TEMPORAL.isRunning()) {
            TEMPORAL.start();
        }
    }

    private static synchronized String tossBaseUrl() {
        if (tossStub == null) {
            try {
                tossStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                tossStub.createContext("/", FullCommerceTestcontainersSupport::handleTossRequest);
                tossStub.setExecutor(Executors.newCachedThreadPool());
                tossStub.start();
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to start Toss HTTP stub", exception);
            }
        }
        return "http://127.0.0.1:" + tossStub.getAddress().getPort();
    }

    private static void handleTossRequest(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(exchange.getRequestMethod()) && "/v1/payouts".equals(path)) {
                PAYOUT_SUBMIT_CALLS.incrementAndGet();
                Map<String, Object> request = OBJECT_MAPPER.readValue(exchange.getRequestBody(), new TypeReference<>() {});
                String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                if (key == null || !key.equals(request.get("refPayoutId"))) {
                    sendJson(exchange, 400, Map.of("code", "INVALID_IDEMPOTENCY_KEY")); return;
                }
                var payout = PAYOUTS.computeIfAbsent(key, ignored -> Map.of("payoutId", "payout-" + java.util.UUID.randomUUID(),
                        "status", "DONE", "refPayoutId", key, "amount", request.get("amount"), "currency", request.get("currency")));
                var barrier = PAYOUT_RESPONSE_BARRIER.getAndSet(null);
                if (barrier != null) {
                    barrier.providerCommitted.countDown();
                    try {
                        if (!barrier.releaseResponse.await(45, java.util.concurrent.TimeUnit.SECONDS)) {
                            exchange.close(); return;
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt(); exchange.close(); return;
                    }
                }
                if (DROP_PAYOUT_RESPONSE.compareAndSet(true, false)) { exchange.close(); return; }
                sendJson(exchange, 200, payout);
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && ("/v1/payouts".equals(path) || path.startsWith("/v1/payouts/"))) {
                PAYOUT_LOOKUP_CALLS.incrementAndGet();
                int failureStatus = PAYOUT_LOOKUP_HTTP_STATUS.get();
                if (failureStatus != 0) {
                    sendJson(exchange, failureStatus, Map.of("code", "PAYOUT_LOOKUP_UNAVAILABLE")); return;
                }
                if ("/v1/payouts".equals(path)) {
                    String key = org.springframework.web.util.UriComponentsBuilder.fromUri(exchange.getRequestURI())
                            .build().getQueryParams().getFirst("refPayoutId");
                    var payout = key == null ? null : PAYOUTS.get(java.net.URLDecoder.decode(key, StandardCharsets.UTF_8));
                    sendJson(exchange, 200, payout == null ? java.util.List.of() : java.util.List.of(payoutReceipt(payout)));
                } else {
                    String id = path.substring("/v1/payouts/".length());
                    var payout = PAYOUTS.values().stream().filter(value -> id.equals(value.get("payoutId"))).findFirst();
                    if (payout.isPresent()) sendJson(exchange, 200, payoutReceipt(payout.get()));
                    else sendJson(exchange, 404, Map.of("code", "PAYOUT_NOT_FOUND"));
                }
                return;
            }
            if ("POST".equals(exchange.getRequestMethod()) && "/v1/payments/confirm".equals(path)) {
                TOSS_CONFIRM_CALLS.incrementAndGet();
                Map<String, Object> request = OBJECT_MAPPER.readValue(exchange.getRequestBody(), new TypeReference<>() {});
                String paymentKey = String.valueOf(request.get("paymentKey"));
                Map<String, Object> payment = TOSS_PAYMENTS.computeIfAbsent(paymentKey, key -> {
                    Map<String, Object> created = new LinkedHashMap<>();
                    created.put("paymentKey", key); created.put("orderId", request.get("orderId")); created.put("status", "DONE");
                    created.put("totalAmount", new BigDecimal(String.valueOf(request.get("amount"))));
                    created.put("balanceAmount", created.get("totalAmount")); created.put("currency", "KRW");
                    created.put("approvedAt", OffsetDateTime.now().toString());
                    created.put("card", Map.of("approveNo", "E2E12345"));
                    created.put("cancels", new java.util.ArrayList<>());
                    return created;
                });
                if (DROP_CONFIRM_RESPONSE.compareAndSet(true, false)) { exchange.close(); return; }
                sendJson(exchange, 200, payment);
                return;
            }
            if ("POST".equals(exchange.getRequestMethod()) && path.matches("/v1/payments/[^/]+/cancel")) {
                TOSS_CANCEL_CALLS.incrementAndGet();
                if (FAIL_CANCEL.get()) { sendJson(exchange, 400, Map.of("code", "NOT_CANCELABLE_PAYMENT", "message", "rejected")); return; }
                String key = path.substring("/v1/payments/".length(), path.length() - "/cancel".length());
                Map<String, Object> payment = TOSS_PAYMENTS.get(key);
                if (payment == null) { sendJson(exchange, 404, Map.of("code", "NOT_FOUND_PAYMENT")); return; }
                Map<String, Object> request = OBJECT_MAPPER.readValue(exchange.getRequestBody(), new TypeReference<>() {});
                String refundKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                synchronized (payment) {
                    if (TOSS_REFUND_KEYS.add(key + ":" + refundKey)) {
                        BigDecimal balance = new BigDecimal(payment.get("balanceAmount").toString());
                        BigDecimal amount = request.get("cancelAmount") == null ? balance : new BigDecimal(request.get("cancelAmount").toString());
                        if (amount.compareTo(balance) > 0) { sendJson(exchange, 400, Map.of("code", "INVALID_CANCEL_AMOUNT")); return; }
                        var cancels = (java.util.List<Object>) payment.get("cancels");
                        cancels.add(Map.of("transactionKey", "cancel-" + java.util.UUID.randomUUID(), "cancelAmount", amount, "cancelStatus", "DONE"));
                        payment.put("balanceAmount", balance.subtract(amount));
                        payment.put("status", balance.compareTo(amount) == 0 ? "CANCELED" : "PARTIAL_CANCELED");
                    }
                }
                // Pause after provider commit, without holding the payment monitor needed by lookups.
                var barrier = CANCEL_RESPONSE_BARRIER.getAndSet(null);
                if (barrier != null) {
                    FAIL_LOOKUPS.set(10000);
                    barrier.providerCommitted.countDown();
                    try {
                        if (!barrier.releaseResponse.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
                            exchange.close();
                            return;
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        exchange.close();
                        return;
                    }
                }
                if (DROP_CANCEL_RESPONSE.compareAndSet(true, false)) { exchange.close(); return; }
                synchronized (payment) { sendJson(exchange, 200, payment); }
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && path.startsWith("/v1/payments/")) {
                if (FAIL_LOOKUPS.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                    sendJson(exchange, 503, Map.of("code", "PROVIDER_UNAVAILABLE")); return;
                }
                Map<String, Object> payment;
                if (path.startsWith("/v1/payments/orders/")) {
                    String orderId = path.substring("/v1/payments/orders/".length());
                    payment = TOSS_PAYMENTS.values().stream().filter(value -> orderId.equals(value.get("orderId"))).findFirst().orElse(null);
                } else { payment = TOSS_PAYMENTS.get(path.substring("/v1/payments/".length())); }
                if (payment == null) { sendJson(exchange, 404, Map.of("code", "NOT_FOUND_PAYMENT")); return; }
                synchronized (payment) { sendJson(exchange, 200, payment); }
                return;
            }
            sendJson(exchange, 404, Map.of("code", "NOT_FOUND"));
        } catch (RuntimeException exception) {
            sendJson(exchange, 500, Map.of("code", "STUB_ERROR"));
        }
    }

    private static Map<String, Object> payoutReceipt(Map<String, Object> stored) {
        Map<String, Object> receipt = new LinkedHashMap<>(stored);
        receipt.putAll(PAYOUT_LOOKUP_OVERRIDES.get());
        return receipt;
    }

    private static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = OBJECT_MAPPER.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}

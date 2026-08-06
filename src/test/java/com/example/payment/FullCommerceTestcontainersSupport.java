package com.example.payment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        registry.add("app.marketplace.realtime.redis-broadcast-enabled", () -> "false");
    }

    static void resetTossStub() {
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
            if ("POST".equals(exchange.getRequestMethod()) && "/v1/payments/confirm".equals(path)) {
                TOSS_CONFIRM_CALLS.incrementAndGet();
                Map<String, Object> request = OBJECT_MAPPER.readValue(
                        exchange.getRequestBody(), new TypeReference<>() { });
                sendJson(exchange, 200, Map.of(
                        "paymentKey", String.valueOf(request.get("paymentKey")),
                        "orderId", String.valueOf(request.get("orderId")),
                        "status", "DONE",
                        "totalAmount", new BigDecimal(String.valueOf(request.get("amount"))),
                        "currency", "KRW",
                        "approvedAt", OffsetDateTime.now().toString(),
                        "card", Map.of("approveNo", "E2E12345")
                ));
                return;
            }
            if ("POST".equals(exchange.getRequestMethod()) && path.matches("/v1/payments/[^/]+/cancel")) {
                TOSS_CANCEL_CALLS.incrementAndGet();
                sendJson(exchange, 200, Map.of("status", "CANCELED"));
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && path.startsWith("/v1/payments/")) {
                String paymentKey = path.substring("/v1/payments/".length());
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("paymentKey", paymentKey);
                response.put("orderId", "ORDER-E2E-STATUS");
                response.put("status", "DONE");
                response.put("totalAmount", 25000);
                response.put("currency", "KRW");
                sendJson(exchange, 200, response);
                return;
            }
            sendJson(exchange, 404, Map.of("code", "NOT_FOUND", "message", path));
        } catch (RuntimeException exception) {
            sendJson(exchange, 500, Map.of("code", "STUB_ERROR", "message", exception.getMessage()));
        }
    }

    private static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = OBJECT_MAPPER.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}

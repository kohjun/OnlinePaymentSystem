package com.example.payment.application.service;

import com.example.payment.infrastructure.persistence.redis.repository.CacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class DistributionReadinessService {

    private final CacheService cacheService;
    private final Environment environment;

    public ReadinessReport evaluate() {
        String mode = property("app.distribution.mode", "DEMO").toUpperCase(Locale.ROOT);
        String brandName = property("app.distribution.brand-name", "EverySale");
        String releaseChannel = property("app.distribution.release-channel", "local-demo");
        boolean productionMode = "PRODUCTION".equals(mode);

        List<ReadinessCheck> checks = new ArrayList<>();
        checks.add(redisCheck());
        checks.add(booleanCheck(
                "temporal-enabled",
                "Temporal Saga baseline",
                boolProperty("app.temporal.enabled", false),
                true,
                "Temporal complete reservation path is enabled.",
                "Temporal complete reservation path is disabled."
        ));
        checks.add(booleanCheck(
                "outbox-enabled",
                "Outbox publisher",
                boolProperty("app.outbox.enabled", false),
                true,
                "Outbox publisher is enabled.",
                "Outbox publisher is disabled."
        ));
        checks.add(booleanCheck(
                "legacy-wal-disabled",
                "Legacy WAL isolation",
                !boolProperty("app.legacy-wal.enabled", false),
                true,
                "Legacy WAL path is disabled.",
                "Legacy WAL path is enabled."
        ));
        checks.add(javaVersionCheck());
        checks.add(gatewayCheck());
        checks.add(tossWebhookCheck(productionMode));
        checks.add(publicCompleteApiCheck(productionMode));
        checks.add(legacyMarketplaceCheckoutCheck(productionMode));
        checks.add(legacyPaymentApiCheck(productionMode));
        checks.add(demoAuthApiCheck(productionMode));
        checks.add(mockAuthenticationCheck(productionMode));
        checks.add(auditTrailCheck(productionMode));
        checks.add(gatewayFallbackCheck(productionMode));
        checks.add(externalAuthCheck());
        checks.add(externalAuthProviderCheck());
        checks.add(corsAllowlistCheck(productionMode));
        checks.add(tenantIsolationCheck());
        checks.add(databaseEndpointCheck(productionMode));
        checks.add(redisEndpointCheck(productionMode));
        checks.add(kafkaEndpointCheck(productionMode));
        checks.add(temporalEndpointCheck(productionMode));
        checks.add(releaseChannelCheck(productionMode, releaseChannel));

        List<String> blockingIssues = checks.stream()
                .filter(check -> check.blocking() && !"PASS".equals(check.status()))
                .map(ReadinessCheck::message)
                .toList();
        List<String> warnings = checks.stream()
                .filter(check -> !check.blocking() && !"PASS".equals(check.status()))
                .map(ReadinessCheck::message)
                .toList();

        String status = blockingIssues.isEmpty()
                ? (warnings.isEmpty() ? "READY" : "ATTENTION_REQUIRED")
                : "BLOCKED";

        return new ReadinessReport(
                status,
                blockingIssues.isEmpty(),
                mode,
                brandName,
                releaseChannel,
                checks,
                blockingIssues,
                warnings,
                System.currentTimeMillis()
        );
    }

    private ReadinessCheck redisCheck() {
        try {
            boolean connected = cacheService.isRedisConnected();
            return new ReadinessCheck(
                    "redis-connectivity",
                    "Redis inventory and queue store",
                    connected ? "PASS" : "FAIL",
                    true,
                    connected ? "Redis is reachable." : "Redis is not reachable."
            );
        } catch (Exception e) {
            return new ReadinessCheck(
                    "redis-connectivity",
                    "Redis inventory and queue store",
                    "FAIL",
                    true,
                    "Redis health check failed: " + e.getMessage()
            );
        }
    }

    private ReadinessCheck javaVersionCheck() {
        String required = property("app.distribution.minimum-java-version", "17");
        String current = System.getProperty("java.specification.version", "unknown");
        boolean pass = isAtLeastJavaVersion(current, required);
        return new ReadinessCheck(
                "java-runtime",
                "Java runtime version",
                pass ? "PASS" : "FAIL",
                true,
                pass
                        ? "Java " + current + " satisfies minimum version " + required + "."
                        : "Java " + current + " is below minimum version " + required + "."
        );
    }

    private ReadinessCheck gatewayCheck() {
        String gateway = property("payment.default-gateway", "TOSS_PAYMENTS");
        boolean requireRealGateway = boolProperty("app.distribution.require-real-payment-gateway", false);
        String normalizedGateway = gateway.toUpperCase(Locale.ROOT);
        boolean mockGateway = normalizedGateway.contains("MOCK");
        boolean tossGateway = normalizedGateway.contains("TOSS");

        if (tossGateway) {
            String clientKey = property("payment.toss.client-key", "");
            String secretKey = property("payment.toss.secret-key", "");
            String mode = property("payment.toss.mode", "test");
            boolean configured = hasText(clientKey) && hasText(secretKey);
            boolean liveMode = "live".equalsIgnoreCase(mode);
            boolean keyModeMatches = ("live".equalsIgnoreCase(mode) && clientKey.startsWith("live_") && secretKey.startsWith("live_"))
                    || ("test".equalsIgnoreCase(mode) && clientKey.startsWith("test_") && secretKey.startsWith("test_"))
                    || (!configured);
            boolean pass = configured && keyModeMatches && (!requireRealGateway || liveMode);
            return new ReadinessCheck(
                    "payment-gateway",
                    "Toss Payments gateway",
                    pass ? "PASS" : (requireRealGateway ? "FAIL" : "WARN"),
                    requireRealGateway && !pass,
                    pass
                            ? "Toss Payments gateway is configured. mode=" + mode
                            : "Toss Payments keys or mode are not ready. mode=" + mode
            );
        }

        if (!mockGateway) {
            return new ReadinessCheck(
                    "payment-gateway",
                    "Payment gateway",
                    "PASS",
                    false,
                    "External payment gateway is configured: " + gateway
            );
        }

        return new ReadinessCheck(
                "payment-gateway",
                "Payment gateway",
                requireRealGateway ? "FAIL" : "WARN",
                requireRealGateway,
                requireRealGateway
                        ? "MockPaymentGateway is not allowed in production distribution mode."
                        : "MockPaymentGateway is enabled for tests or local demos only."
        );
    }

    private ReadinessCheck publicCompleteApiCheck(boolean productionMode) {
        boolean enabled = boolProperty("app.checkout.public-complete-enabled", false);
        boolean pass = !enabled;
        return new ReadinessCheck(
                "public-complete-api-disabled",
                "Direct complete reservation API",
                pass ? "PASS" : (productionMode ? "FAIL" : "WARN"),
                productionMode && !pass,
                pass
                        ? "Direct /api/reservations/complete is disabled for public checkout."
                        : "Direct /api/reservations/complete is exposed and can bypass Toss confirm."
        );
    }

    private ReadinessCheck tossWebhookCheck(boolean productionMode) {
        boolean enabled = boolProperty("payment.toss.webhook.enabled", false);
        String token = property("payment.toss.webhook.path-token", "");
        boolean tokenStrong = hasText(token) && token.length() >= 32 && !looksUnresolved(token);
        boolean pass = enabled && tokenStrong;
        return new ReadinessCheck(
                "toss-webhook-configured",
                "Toss Payments webhook",
                pass ? "PASS" : (productionMode ? "FAIL" : "WARN"),
                productionMode && !pass,
                pass
                        ? "Toss webhook endpoint is enabled with a high-entropy path token."
                        : "Toss webhook endpoint is not ready. Enable it and set TOSS_WEBHOOK_PATH_TOKEN to at least 32 characters."
        );
    }

    private ReadinessCheck legacyPaymentApiCheck(boolean productionMode) {
        boolean enabled = boolProperty("payment.legacy-api.enabled", false);
        boolean pass = !enabled;
        return new ReadinessCheck(
                "legacy-payment-api-disabled",
                "Legacy payment process/retry/refund API",
                pass ? "PASS" : (productionMode ? "FAIL" : "WARN"),
                productionMode && !pass,
                pass
                        ? "Legacy payment APIs are disabled."
                        : "Legacy payment APIs are enabled and can confuse Toss checkout."
        );
    }

    private ReadinessCheck legacyMarketplaceCheckoutCheck(boolean productionMode) {
        boolean enabled = boolProperty("app.checkout.legacy-marketplace-enabled", false);
        boolean pass = !enabled;
        return new ReadinessCheck(
                "legacy-marketplace-checkout-disabled",
                "Legacy marketplace checkout API",
                pass ? "PASS" : (productionMode ? "FAIL" : "WARN"),
                productionMode && !pass,
                pass
                        ? "Legacy marketplace checkout APIs are disabled."
                        : "Legacy marketplace checkout APIs are enabled and can bypass Toss confirm."
        );
    }

    private ReadinessCheck demoAuthApiCheck(boolean productionMode) {
        boolean enabled = boolProperty("app.simulation.auth.enabled", false);
        boolean pass = !enabled;
        return new ReadinessCheck(
                "demo-auth-api-disabled",
                "Demo simulation authentication API",
                pass ? "PASS" : (productionMode ? "FAIL" : "WARN"),
                productionMode && !pass,
                pass
                        ? "Demo simulation authentication API is disabled."
                        : "Demo simulation authentication API is enabled and uses static demo credentials."
        );
    }


    private ReadinessCheck mockAuthenticationCheck(boolean productionMode) {
        boolean enabled = boolProperty("app.security.mock-auth.enabled", false);
        boolean pass = !enabled;
        return new ReadinessCheck(
                "mock-auth-disabled",
                "Mock authentication filter",
                pass ? "PASS" : (productionMode ? "FAIL" : "WARN"),
                productionMode && !pass,
                pass
                        ? "Mock authentication filter is disabled."
                        : "Mock authentication filter is enabled and accepts client-supplied identities."
        );
    }
    private ReadinessCheck auditTrailCheck(boolean productionMode) {
        boolean enabled = boolProperty("app.audit.enabled", true);
        return new ReadinessCheck(
                "security-audit-enabled",
                "Security audit trail",
                enabled ? "PASS" : (productionMode ? "FAIL" : "WARN"),
                productionMode && !enabled,
                enabled
                        ? "Security audit trail is enabled."
                        : "Security audit trail is disabled."
        );
    }

    private ReadinessCheck gatewayFallbackCheck(boolean productionMode) {
        boolean fallbackEnabled = boolProperty("payment.allow-gateway-fallback", false);
        boolean pass = !fallbackEnabled;
        return new ReadinessCheck(
                "payment-gateway-fallback-disabled",
                "Payment gateway fallback",
                pass ? "PASS" : (productionMode ? "FAIL" : "WARN"),
                productionMode && !pass,
                pass
                        ? "Payment gateway fallback is disabled."
                        : "Payment gateway fallback is enabled."
        );
    }

    private ReadinessCheck externalAuthCheck() {
        boolean requireExternalAuth = boolProperty("app.distribution.require-external-auth", false);
        boolean externalAuthEnabled = boolProperty("app.security.external-auth.enabled", false);
        boolean pass = !requireExternalAuth || externalAuthEnabled;
        return new ReadinessCheck(
                "external-auth",
                "External authentication",
                pass ? "PASS" : "FAIL",
                requireExternalAuth,
                pass ? "External authentication requirement is satisfied." : "External authentication is required."
        );
    }

    private ReadinessCheck externalAuthProviderCheck() {
        boolean externalAuthEnabled = boolProperty("app.security.external-auth.enabled", false);
        boolean requireExternalAuth = boolProperty("app.distribution.require-external-auth", false);
        if (!externalAuthEnabled) {
            return new ReadinessCheck(
                    "external-auth-provider",
                    "External authentication provider",
                    "PASS",
                    false,
                    "External authentication provider is not required while external auth is disabled."
            );
        }

        boolean configured = hasText(property("spring.security.oauth2.resourceserver.jwt.issuer-uri", ""))
                || hasText(property("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", ""));
        return new ReadinessCheck(
                "external-auth-provider",
                "External authentication provider",
                configured ? "PASS" : (requireExternalAuth ? "FAIL" : "WARN"),
                requireExternalAuth && !configured,
                configured
                        ? "JWT issuer or JWK Set URI is configured."
                        : "External auth is enabled but JWT issuer/JWK Set URI is missing."
        );
    }

    private ReadinessCheck corsAllowlistCheck(boolean productionMode) {
        boolean enabled = boolProperty("app.security.cors.enabled", true);
        List<String> entries = new ArrayList<>();
        entries.addAll(csvProperty("app.security.cors.allowed-origins"));
        entries.addAll(csvProperty("app.security.cors.allowed-origin-patterns"));

        boolean unsafe = entries.stream().anyMatch(this::isUnsafeCorsEntry);
        boolean local = entries.stream().anyMatch(this::isLocalEndpoint);
        boolean unresolved = entries.stream().anyMatch(this::looksUnresolved);
        boolean insecureHttp = entries.stream().anyMatch(this::isInsecureHttpOrigin);
        boolean pass = !productionMode || !enabled || (!unsafe && !local && !unresolved && !insecureHttp);

        return new ReadinessCheck(
                "cors-allowlist",
                "CORS allowlist",
                pass ? "PASS" : "FAIL",
                productionMode && !pass,
                pass
                        ? "CORS is disabled or restricted to production-safe origins."
                        : "CORS allowlist contains unsafe production origins. Use explicit HTTPS origins and avoid wildcard or localhost entries."
        );
    }
    private ReadinessCheck tenantIsolationCheck() {
        boolean requireTenantIsolation = boolProperty("app.distribution.require-tenant-isolation", false);
        boolean requireTenantHeader = boolProperty("app.tenancy.require-tenant-header", false);
        boolean pass = !requireTenantIsolation || requireTenantHeader;
        return new ReadinessCheck(
                "tenant-isolation",
                "Tenant isolation",
                pass ? "PASS" : "FAIL",
                requireTenantIsolation,
                pass ? "Tenant isolation requirement is satisfied." : "Tenant header isolation is required."
        );
    }

    private ReadinessCheck databaseEndpointCheck(boolean productionMode) {
        return endpointCheck(
                productionMode,
                "database-endpoint",
                "Database endpoint",
                property("spring.datasource.url", ""),
                "Database endpoint matches distribution mode.",
                "Production mode requires a configured non-local database endpoint."
        );
    }

    private ReadinessCheck redisEndpointCheck(boolean productionMode) {
        return endpointCheck(
                productionMode,
                "redis-endpoint",
                "Redis endpoint",
                property("spring.data.redis.host", ""),
                "Redis endpoint matches distribution mode.",
                "Production mode requires a configured non-local Redis endpoint."
        );
    }

    private ReadinessCheck kafkaEndpointCheck(boolean productionMode) {
        return endpointCheck(
                productionMode,
                "kafka-endpoint",
                "Kafka endpoint",
                property("spring.kafka.bootstrap-servers", ""),
                "Kafka endpoint matches distribution mode.",
                "Production mode requires configured non-local Kafka bootstrap servers."
        );
    }

    private ReadinessCheck temporalEndpointCheck(boolean productionMode) {
        return endpointCheck(
                productionMode,
                "temporal-endpoint",
                "Temporal endpoint",
                property("app.temporal.target", ""),
                "Temporal endpoint matches distribution mode.",
                "Production mode requires a configured non-local Temporal endpoint."
        );
    }

    private ReadinessCheck endpointCheck(boolean productionMode,
                                         String id,
                                         String name,
                                         String value,
                                         String passMessage,
                                         String failMessage) {
        boolean configured = hasText(value) && !looksUnresolved(value);
        boolean localEndpoint = configured && isLocalEndpoint(value);
        boolean pass = !productionMode || (configured && !localEndpoint);
        return new ReadinessCheck(
                id,
                name,
                pass ? "PASS" : "FAIL",
                productionMode,
                pass ? passMessage : failMessage
        );
    }

    private ReadinessCheck releaseChannelCheck(boolean productionMode, String releaseChannel) {
        boolean localChannel = releaseChannel.toLowerCase(Locale.ROOT).contains("local");
        boolean pass = !productionMode || !localChannel;
        return new ReadinessCheck(
                "release-channel",
                "Release channel",
                pass ? "PASS" : "FAIL",
                productionMode,
                pass ? "Release channel matches distribution mode." : "Production mode cannot use a local release channel."
        );
    }

    private ReadinessCheck booleanCheck(String id, String name, boolean condition, boolean blocking,
                                        String passMessage, String failMessage) {
        return new ReadinessCheck(id, name, condition ? "PASS" : "FAIL", blocking,
                condition ? passMessage : failMessage);
    }

    private String property(String key, String defaultValue) {
        return environment.getProperty(key, defaultValue);
    }

    private boolean boolProperty(String key, boolean defaultValue) {
        return environment.getProperty(key, Boolean.class, defaultValue);
    }

    private List<String> csvProperty(String key) {
        String value = property(key, "");
        if (!hasText(value)) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .toList();
    }

    private boolean isUnsafeCorsEntry(String value) {
        return "*".equals(value);
    }

    private boolean isInsecureHttpOrigin(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).startsWith("http://");
    }
    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private boolean looksUnresolved(String value) {
        return value != null && value.contains("${");
    }

    private boolean isLocalEndpoint(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("localhost")
                || normalized.contains("127.0.0.1")
                || normalized.contains("0.0.0.0")
                || normalized.contains("host.docker.internal")
                || normalized.contains(":mem:");
    }

    private boolean isAtLeastJavaVersion(String current, String required) {
        try {
            int currentMajor = parseJavaMajor(current);
            int requiredMajor = parseJavaMajor(required);
            return currentMajor >= requiredMajor;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private int parseJavaMajor(String version) {
        if (version.startsWith("1.")) {
            return Integer.parseInt(version.substring(2, 3));
        }
        int dot = version.indexOf('.');
        return Integer.parseInt(dot > 0 ? version.substring(0, dot) : version);
    }

    public record ReadinessReport(
            String status,
            boolean releasable,
            String mode,
            String brandName,
            String releaseChannel,
            List<ReadinessCheck> checks,
            List<String> blockingIssues,
            List<String> warnings,
            long timestamp
    ) {
    }

    public record ReadinessCheck(
            String id,
            String name,
            String status,
            boolean blocking,
            String message
    ) {
    }
}

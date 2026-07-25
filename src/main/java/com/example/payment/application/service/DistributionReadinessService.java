package com.example.payment.application.service;

import com.example.payment.infrastructure.persistence.redis.repository.CacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
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
    private final ObjectProvider<SellerPayoutTransferGateway> payoutGatewayProvider;

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
        checks.add(payoutTransferCheck(productionMode));
        checks.add(operationalWorkerCheck(
                productionMode,
                "seller-payout-reconciliation-enabled",
                "Seller payout reconciliation worker",
                "app.payout.transfer.reconciliation.enabled",
                "Seller payout reconciliation is enabled.",
                "Seller payout reconciliation is disabled; unknown bank transfers can remain unresolved."
        ));
        checks.add(tossWebhookCheck(productionMode));
        checks.add(operationalWorkerCheck(
                productionMode,
                "temporal-worker-enabled",
                "Temporal workflow worker",
                "app.temporal.worker-enabled",
                "Temporal worker is enabled.",
                "Temporal worker is disabled; confirmed payments cannot complete the reservation Saga."
        ));
        checks.add(operationalWorkerCheck(
                productionMode,
                "inventory-reconciliation-enabled",
                "Inventory reconciliation worker",
                "app.inventory.reconciliation.enabled",
                "Inventory reconciliation is enabled.",
                "Inventory reconciliation is disabled; Redis and Postgres drift may remain undetected."
        ));
        checks.add(operationalWorkerCheck(
                productionMode,
                "toss-reconciliation-enabled",
                "Toss payment reconciliation worker",
                "payment.toss.reconciliation.enabled",
                "Toss payment reconciliation is enabled.",
                "Toss payment reconciliation is disabled; missing browser redirects can leave payments unresolved."
        ));
        checks.add(operationalWorkerCheck(
                productionMode,
                "auction-auto-close-enabled",
                "Auction auto-close worker",
                "app.auction.auto-close-enabled",
                "Auction auto-close is enabled.",
                "Auction auto-close is disabled; ended auctions require manual intervention."
        ));
        checks.add(operationalWorkerCheck(
                productionMode,
                "marketplace-lifecycle-enabled",
                "Marketplace sale-event lifecycle worker",
                "app.marketplace.lifecycle.enabled",
                "Raffle, fixed-price, and drop lifecycle processing is enabled.",
                "Marketplace lifecycle processing is disabled; expired sale events can remain live."
        ));
        checks.add(operationalWorkerCheck(
                productionMode,
                "marketplace-realtime-broadcast-enabled",
                "Distributed marketplace realtime broadcast",
                "app.marketplace.realtime.redis-broadcast-enabled",
                "Redis marketplace realtime broadcast is enabled.",
                "Distributed marketplace realtime broadcast is disabled; multi-instance SSE clients can miss events."
        ));
        checks.add(flywayMigrationCheck(productionMode));
        checks.add(schemaValidationCheck(productionMode));
        checks.add(publicCompleteApiCheck(productionMode));
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

    private ReadinessCheck payoutTransferCheck(boolean productionMode) {
        String provider = property("app.payout.transfer.provider", "LEDGER_ONLY").trim();
        boolean ledgerOnly = provider.isEmpty() || "LEDGER_ONLY".equalsIgnoreCase(provider);
        boolean externalAdapterEnabled = boolProperty("app.payout.transfer.external-adapter-enabled", false);
        SellerPayoutTransferGateway gateway = null;
        try {
            gateway = payoutGatewayProvider.getIfAvailable();
        } catch (RuntimeException ignored) {
            // Multiple or invalid gateway beans are treated as not configured.
        }
        String registeredProvider = gateway == null ? null : gateway.providerName();
        boolean matchingGatewayBean = registeredProvider != null
                && !registeredProvider.isBlank()
                && provider.equalsIgnoreCase(registeredProvider);
        boolean pass = !ledgerOnly && externalAdapterEnabled && matchingGatewayBean;
        return new ReadinessCheck(
                "seller-payout-transfer-provider",
                "Seller payout transfer provider",
                pass ? "PASS" : (productionMode ? "FAIL" : "WARN"),
                productionMode && !pass,
                pass
                        ? "External seller payout transfer adapter is configured: " + provider
                        : "Configure an external payout gateway bean whose providerName matches app.payout.transfer.provider before production."
        );
    }

    private ReadinessCheck operationalWorkerCheck(boolean productionMode,
                                                   String id,
                                                   String name,
                                                   String propertyName,
                                                   String passMessage,
                                                   String failMessage) {
        boolean enabled = boolProperty(propertyName, false);
        return new ReadinessCheck(
                id,
                name,
                enabled ? "PASS" : (productionMode ? "FAIL" : "WARN"),
                productionMode && !enabled,
                enabled ? passMessage : failMessage
        );
    }

    private ReadinessCheck flywayMigrationCheck(boolean productionMode) {
        boolean enabled = boolProperty("spring.flyway.enabled", true);
        return new ReadinessCheck(
                "flyway-migrations-enabled",
                "Database migrations",
                enabled ? "PASS" : (productionMode ? "FAIL" : "WARN"),
                productionMode && !enabled,
                enabled
                        ? "Flyway database migrations are enabled."
                        : "Flyway database migrations are disabled."
        );
    }

    private ReadinessCheck schemaValidationCheck(boolean productionMode) {
        String ddlAuto = property("spring.jpa.hibernate.ddl-auto", "");
        boolean validateOnly = "validate".equalsIgnoreCase(ddlAuto);
        return new ReadinessCheck(
                "schema-validation-mode",
                "JPA schema management",
                validateOnly ? "PASS" : (productionMode ? "FAIL" : "WARN"),
                productionMode && !validateOnly,
                validateOnly
                        ? "JPA validates the migrated schema without mutating it."
                        : "Production must use spring.jpa.hibernate.ddl-auto=validate."
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

        boolean issuerConfigured = hasText(property("spring.security.oauth2.resourceserver.jwt.issuer-uri", ""))
                || hasText(property("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", ""));
        boolean audienceConfigured = hasText(property("app.security.external-auth.audience", ""));
        boolean configured = issuerConfigured && audienceConfigured;
        return new ReadinessCheck(
                "external-auth-provider",
                "External authentication provider",
                configured ? "PASS" : (requireExternalAuth ? "FAIL" : "WARN"),
                requireExternalAuth && !configured,
                configured
                        ? "JWT issuer/JWK Set URI and audience validation are configured."
                        : "External auth requires both a JWT issuer/JWK Set URI and OIDC audience."
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
        String tenancyMode = property("app.tenancy.mode", "DEMO").trim();
        boolean singleTenant = "SINGLE_TENANT".equalsIgnoreCase(tenancyMode);
        boolean multiTenantRls = "MULTI_TENANT_RLS".equalsIgnoreCase(tenancyMode);
        boolean allowedTenantConfigured = hasText(property("app.tenancy.allowed-tenant-id", ""));
        boolean databaseRlsEnabled = boolProperty("app.tenancy.database-rls-enabled", false);
        boolean requireTenantHeader = boolProperty("app.tenancy.require-tenant-header", false);
        boolean bindTokenClaims = boolProperty("app.tenancy.bind-token-claims", false);
        boolean requireTenantClaim = boolProperty("app.tenancy.require-token-tenant-claim", false);
        boolean requirePartnerClaim = boolProperty("app.tenancy.require-token-partner-claim", false);
        boolean singleTenantBoundary = singleTenant
                && allowedTenantConfigured
                && bindTokenClaims
                && requireTenantClaim;
        boolean multiTenantBoundary = multiTenantRls
                && databaseRlsEnabled
                && requireTenantHeader
                && bindTokenClaims
                && requireTenantClaim
                && requirePartnerClaim;
        boolean pass = !requireTenantIsolation || singleTenantBoundary || multiTenantBoundary;
        String detail = !requireTenantIsolation
                ? "Tenant isolation is not required for this distribution mode."
                : singleTenantBoundary
                        ? "Single-tenant deployment is pinned to an allowed tenant and authenticated tenant claim."
                        : multiTenantBoundary
                                ? "Multi-tenant deployment uses database RLS and matching JWT tenant/partner claims."
                                : "Choose SINGLE_TENANT with an allowed tenant and JWT tenant claim, or enable verified MULTI_TENANT_RLS isolation.";
        return new ReadinessCheck(
                "tenant-isolation",
                "Tenant isolation",
                pass ? "PASS" : "FAIL",
                requireTenantIsolation,
                detail
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

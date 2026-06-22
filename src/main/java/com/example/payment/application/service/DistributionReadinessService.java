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
        String brandName = property("app.distribution.brand-name", "에브리세일");
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
        checks.add(externalAuthCheck());
        checks.add(tenantIsolationCheck());
        checks.add(databaseEndpointCheck(productionMode));
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
        String url = property("spring.datasource.url", "");
        boolean localDatabase = url.contains("localhost") || url.contains("127.0.0.1") || url.contains(":mem:");
        boolean pass = !productionMode || !localDatabase;
        return new ReadinessCheck(
                "database-endpoint",
                "Database endpoint",
                pass ? "PASS" : "FAIL",
                productionMode,
                pass ? "Database endpoint matches distribution mode." : "Production mode cannot use a local database endpoint."
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

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
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

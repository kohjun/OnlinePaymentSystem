package com.example.payment.application.service;

import com.example.payment.infrastructure.persistence.redis.repository.CacheService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DistributionReadinessServiceTest {

    @Test
    void demoModeAllowsMockGatewayButReturnsAttentionRequired() {
        CacheService cacheService = mock(CacheService.class);
        when(cacheService.isRedisConnected()).thenReturn(true);

        MockEnvironment environment = baseEnvironment()
                .withProperty("app.distribution.mode", "DEMO")
                .withProperty("payment.default-gateway", "MOCK_PAYMENT_GATEWAY")
                .withProperty("app.distribution.require-real-payment-gateway", "false");

        DistributionReadinessService.ReadinessReport report =
                new DistributionReadinessService(cacheService, environment).evaluate();

        assertEquals("ATTENTION_REQUIRED", report.status());
        assertTrue(report.releasable());
        assertTrue(report.blockingIssues().isEmpty());
        assertFalse(report.warnings().isEmpty());
    }

    @Test
    void productionModeBlocksUnsafeLocalDefaults() {
        CacheService cacheService = mock(CacheService.class);
        when(cacheService.isRedisConnected()).thenReturn(true);

        MockEnvironment environment = baseEnvironment()
                .withProperty("app.distribution.mode", "PRODUCTION")
                .withProperty("app.distribution.release-channel", "local-demo")
                .withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5434/payment")
                .withProperty("spring.data.redis.host", "localhost")
                .withProperty("spring.kafka.bootstrap-servers", "localhost:9092")
                .withProperty("app.temporal.target", "localhost:7233")
                .withProperty("payment.default-gateway", "MOCK_PAYMENT_GATEWAY")
                .withProperty("app.checkout.public-complete-enabled", "true")
                .withProperty("app.checkout.legacy-marketplace-enabled", "true")
                .withProperty("payment.legacy-api.enabled", "true")
                .withProperty("payment.allow-gateway-fallback", "true")
                .withProperty("app.simulation.auth.enabled", "true")
                .withProperty("app.security.mock-auth.enabled", "true")
                .withProperty("app.security.cors.allowed-origins", "*,http://localhost:3000,http://frontend.example.com")
                .withProperty("app.audit.enabled", "false")
                .withProperty("app.distribution.require-real-payment-gateway", "true")
                .withProperty("app.distribution.require-external-auth", "true")
                .withProperty("app.distribution.require-tenant-isolation", "true")
                .withProperty("app.tenancy.require-tenant-header", "false");

        DistributionReadinessService.ReadinessReport report =
                new DistributionReadinessService(cacheService, environment).evaluate();

        assertEquals("BLOCKED", report.status());
        assertFalse(report.releasable());
        assertTrue(report.blockingIssues().size() >= 10);
        assertTrue(report.blockingIssues().stream()
                .anyMatch(issue -> issue.contains("Demo simulation authentication API is enabled")));
        assertTrue(report.blockingIssues().stream()
                .anyMatch(issue -> issue.contains("Security audit trail is disabled")));
        assertTrue(report.blockingIssues().stream()
                .anyMatch(issue -> issue.contains("Mock authentication filter is enabled")));
        assertTrue(report.blockingIssues().stream()
                .anyMatch(issue -> issue.contains("non-local database endpoint")));
        assertTrue(report.blockingIssues().stream()
                .anyMatch(issue -> issue.contains("non-local Redis endpoint")));
        assertTrue(report.blockingIssues().stream()
                .anyMatch(issue -> issue.contains("non-local Kafka bootstrap servers")));
        assertTrue(report.blockingIssues().stream()
                .anyMatch(issue -> issue.contains("non-local Temporal endpoint")));
        assertTrue(report.blockingIssues().stream()
                .anyMatch(issue -> issue.contains("CORS allowlist contains unsafe")));
    }

    @Test
    void productionModeBlocksMissingExternalAuthProvider() {
        CacheService cacheService = mock(CacheService.class);
        when(cacheService.isRedisConnected()).thenReturn(true);

        MockEnvironment environment = baseEnvironment()
                .withProperty("app.distribution.mode", "PRODUCTION")
                .withProperty("app.distribution.require-external-auth", "true")
                .withProperty("app.security.external-auth.enabled", "true");

        DistributionReadinessService.ReadinessReport report =
                new DistributionReadinessService(cacheService, environment).evaluate();

        assertEquals("BLOCKED", report.status());
        assertTrue(report.blockingIssues().stream()
                .anyMatch(issue -> issue.contains("JWT issuer/JWK Set URI is missing")));
    }

    private MockEnvironment baseEnvironment() {
        return new MockEnvironment()
                .withProperty("app.distribution.brand-name", "EverySale")
                .withProperty("app.distribution.release-channel", "test")
                .withProperty("app.distribution.minimum-java-version", "17")
                .withProperty("app.temporal.enabled", "true")
                .withProperty("app.outbox.enabled", "true")
                .withProperty("app.legacy-wal.enabled", "false")
                .withProperty("app.checkout.public-complete-enabled", "false")
                .withProperty("app.checkout.legacy-marketplace-enabled", "false")
                .withProperty("payment.legacy-api.enabled", "false")
                .withProperty("payment.allow-gateway-fallback", "false")
                .withProperty("app.simulation.auth.enabled", "false")
                .withProperty("app.audit.enabled", "true")
                .withProperty("spring.datasource.url", "jdbc:postgresql://db.example.com:5432/payment")
                .withProperty("spring.data.redis.host", "redis.example.com")
                .withProperty("spring.kafka.bootstrap-servers", "kafka-1.example.com:9092,kafka-2.example.com:9092")
                .withProperty("app.temporal.target", "temporal.example.com:7233")
                .withProperty("app.security.cors.allowed-origins", "https://app.example.com,https://admin.example.com")
                .withProperty("app.security.external-auth.enabled", "false")
                .withProperty("app.tenancy.require-tenant-header", "true");
    }
}

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
                .withProperty("payment.default-gateway", "MOCK_PAYMENT_GATEWAY")
                .withProperty("app.distribution.require-real-payment-gateway", "true")
                .withProperty("app.distribution.require-external-auth", "true")
                .withProperty("app.distribution.require-tenant-isolation", "true")
                .withProperty("app.tenancy.require-tenant-header", "false");

        DistributionReadinessService.ReadinessReport report =
                new DistributionReadinessService(cacheService, environment).evaluate();

        assertEquals("BLOCKED", report.status());
        assertFalse(report.releasable());
        assertTrue(report.blockingIssues().size() >= 4);
    }

    private MockEnvironment baseEnvironment() {
        return new MockEnvironment()
                .withProperty("app.distribution.brand-name", "에브리세일")
                .withProperty("app.distribution.release-channel", "test")
                .withProperty("app.distribution.minimum-java-version", "17")
                .withProperty("app.temporal.enabled", "true")
                .withProperty("app.outbox.enabled", "true")
                .withProperty("app.legacy-wal.enabled", "false")
                .withProperty("spring.datasource.url", "jdbc:postgresql://db.example.com:5432/payment")
                .withProperty("app.security.external-auth.enabled", "false")
                .withProperty("app.tenancy.require-tenant-header", "true");
    }
}

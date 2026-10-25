package com.example.payment.application.service;

import com.example.payment.infrastructure.persistence.redis.repository.CacheService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DistributionReadinessServiceTest {

    private final ObjectProvider<SellerPayoutTransferGateway> payoutGatewayProvider = mock(ObjectProvider.class);

    DistributionReadinessServiceTest() {
        SellerPayoutTransferGateway gateway = mock(SellerPayoutTransferGateway.class);
        when(gateway.providerName()).thenReturn("TEST_EXTERNAL");
        when(payoutGatewayProvider.getIfAvailable()).thenReturn(gateway);
    }

    @Test
    void demoModeAllowsMockGatewayButReturnsAttentionRequired() {
        CacheService cacheService = mock(CacheService.class);
        when(cacheService.isRedisConnected()).thenReturn(true);

        MockEnvironment environment = baseEnvironment()
                .withProperty("app.distribution.mode", "DEMO")
                .withProperty("payment.default-gateway", "MOCK_PAYMENT_GATEWAY")
                .withProperty("app.distribution.require-real-payment-gateway", "false");

        DistributionReadinessService.ReadinessReport report =
                new DistributionReadinessService(cacheService, environment, payoutGatewayProvider).evaluate();

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
                .withProperty("app.checkout.legacy-reservation-api-enabled", "true")
                .withProperty("payment.allow-gateway-fallback", "true")
                .withProperty("app.security.mock-auth.enabled", "true")
                .withProperty("app.security.cors.allowed-origins", "*,http://localhost:3000,http://frontend.example.com")
                .withProperty("app.audit.enabled", "false")
                .withProperty("app.distribution.require-real-payment-gateway", "true")
                .withProperty("app.distribution.require-external-auth", "true")
                .withProperty("app.distribution.require-tenant-isolation", "true")
                .withProperty("app.tenancy.require-tenant-header", "false");

        DistributionReadinessService.ReadinessReport report =
                new DistributionReadinessService(cacheService, environment, payoutGatewayProvider).evaluate();

        assertEquals("BLOCKED", report.status());
        assertFalse(report.releasable());
        assertTrue(report.blockingIssues().size() >= 10);
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
        assertTrue(report.blockingIssues().stream()
                .anyMatch(issue -> issue.contains("Direct reservation mutation APIs")));
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
                new DistributionReadinessService(cacheService, environment, payoutGatewayProvider).evaluate();

        assertEquals("BLOCKED", report.status());
        assertTrue(report.blockingIssues().stream()
                .anyMatch(issue -> issue.contains("JWT issuer/JWK Set URI and OIDC audience")));
    }

    @Test
    void productionModeBlocksMissingFrontendOidcPkceConfiguration() {
        CacheService cacheService = mock(CacheService.class);
        when(cacheService.isRedisConnected()).thenReturn(true);

        MockEnvironment environment = baseEnvironment()
                .withProperty("app.distribution.mode", "PRODUCTION")
                .withProperty("app.distribution.require-external-auth", "true")
                .withProperty("app.security.external-auth.enabled", "true")
                .withProperty("app.security.external-auth.frontend.client-id", "");

        DistributionReadinessService.ReadinessCheck check =
                new DistributionReadinessService(cacheService, environment, payoutGatewayProvider).evaluate().checks().stream()
                        .filter(candidate -> "external-auth-frontend".equals(candidate.id()))
                        .findFirst()
                        .orElseThrow();

        assertEquals("FAIL", check.status());
        assertTrue(check.blocking());
    }

    @Test
    void productionModeBlocksDisabledRecoveryWorkersAndUnsafeSchemaMode() {
        CacheService cacheService = mock(CacheService.class);
        when(cacheService.isRedisConnected()).thenReturn(true);

        MockEnvironment environment = baseEnvironment()
                .withProperty("app.distribution.mode", "PRODUCTION")
                .withProperty("app.temporal.worker-enabled", "false")
                .withProperty("app.inventory.reconciliation.enabled", "false")
                .withProperty("payment.toss.reconciliation.enabled", "false")
                .withProperty("app.auction.auto-close-enabled", "false")
                .withProperty("app.marketplace.realtime.redis-broadcast-enabled", "false")
                .withProperty("spring.flyway.enabled", "false")
                .withProperty("spring.jpa.hibernate.ddl-auto", "update");

        DistributionReadinessService.ReadinessReport report =
                new DistributionReadinessService(cacheService, environment, payoutGatewayProvider).evaluate();

        assertEquals("BLOCKED", report.status());
        assertTrue(report.blockingIssues().stream().anyMatch(issue -> issue.contains("Temporal worker is disabled")));
        assertTrue(report.blockingIssues().stream().anyMatch(issue -> issue.contains("Inventory reconciliation is disabled")));
        assertTrue(report.blockingIssues().stream().anyMatch(issue -> issue.contains("Toss payment reconciliation is disabled")));
        assertTrue(report.blockingIssues().stream().anyMatch(issue -> issue.contains("Auction auto-close is disabled")));
        assertTrue(report.blockingIssues().stream().anyMatch(issue -> issue.contains("Distributed marketplace realtime broadcast is disabled")));
        assertTrue(report.blockingIssues().stream().anyMatch(issue -> issue.contains("Flyway database migrations are disabled")));
        assertTrue(report.blockingIssues().stream().anyMatch(issue -> issue.contains("ddl-auto=validate")));
    }

    @Test
    void productionModeBlocksLocalMarketplaceMediaStorage() {
        CacheService cacheService = mock(CacheService.class);
        when(cacheService.isRedisConnected()).thenReturn(true);

        MockEnvironment environment = baseEnvironment()
                .withProperty("app.distribution.mode", "PRODUCTION")
                .withProperty("app.media.storage-provider", "LOCAL");

        DistributionReadinessService.ReadinessCheck check =
                new DistributionReadinessService(cacheService, environment, payoutGatewayProvider).evaluate().checks().stream()
                        .filter(candidate -> "marketplace-media-storage".equals(candidate.id()))
                        .findFirst()
                        .orElseThrow();

        assertEquals("FAIL", check.status());
        assertTrue(check.blocking());
    }

    @Test
    void productionModeAcceptsEncryptedS3MarketplaceMediaStorage() {
        CacheService cacheService = mock(CacheService.class);
        when(cacheService.isRedisConnected()).thenReturn(true);

        MockEnvironment environment = baseEnvironment()
                .withProperty("app.distribution.mode", "PRODUCTION");

        DistributionReadinessService.ReadinessCheck check =
                new DistributionReadinessService(cacheService, environment, payoutGatewayProvider).evaluate().checks().stream()
                        .filter(candidate -> "marketplace-media-storage".equals(candidate.id()))
                        .findFirst()
                        .orElseThrow();

        assertEquals("PASS", check.status());
        assertFalse(check.blocking());
    }

    @Test
    void productionModeBlocksDisabledMediaMalwareScanning() {
        CacheService cacheService = mock(CacheService.class);
        when(cacheService.isRedisConnected()).thenReturn(true);

        MockEnvironment environment = baseEnvironment()
                .withProperty("app.distribution.mode", "PRODUCTION")
                .withProperty("app.media.malware-scan.provider", "NONE");

        DistributionReadinessService.ReadinessCheck check =
                new DistributionReadinessService(cacheService, environment, payoutGatewayProvider).evaluate().checks().stream()
                        .filter(candidate -> "marketplace-media-malware-scan".equals(candidate.id()))
                        .findFirst()
                        .orElseThrow();

        assertEquals("FAIL", check.status());
        assertTrue(check.blocking());
    }

    @Test
    void productionModeAcceptsValidatedClamAvMediaScanning() {
        CacheService cacheService = mock(CacheService.class);
        when(cacheService.isRedisConnected()).thenReturn(true);

        MockEnvironment environment = baseEnvironment()
                .withProperty("app.distribution.mode", "PRODUCTION");

        DistributionReadinessService.ReadinessCheck check =
                new DistributionReadinessService(cacheService, environment, payoutGatewayProvider).evaluate().checks().stream()
                        .filter(candidate -> "marketplace-media-malware-scan".equals(candidate.id()))
                        .findFirst()
                        .orElseThrow();

        assertEquals("PASS", check.status());
        assertFalse(check.blocking());
    }

    @Test
    void singleTenantModePassesWithPinnedTenantAndJwtTenantClaim() {
        CacheService cacheService = mock(CacheService.class);
        when(cacheService.isRedisConnected()).thenReturn(true);

        MockEnvironment environment = baseEnvironment()
                .withProperty("app.distribution.require-tenant-isolation", "true")
                .withProperty("app.tenancy.mode", "SINGLE_TENANT")
                .withProperty("app.tenancy.allowed-tenant-id", "everysale")
                .withProperty("app.tenancy.bind-token-claims", "true")
                .withProperty("app.tenancy.require-token-tenant-claim", "true");

        DistributionReadinessService.ReadinessCheck check =
                new DistributionReadinessService(cacheService, environment, payoutGatewayProvider).evaluate().checks().stream()
                        .filter(candidate -> "tenant-isolation".equals(candidate.id()))
                        .findFirst()
                        .orElseThrow();

        assertEquals("PASS", check.status());
        assertTrue(check.message().contains("Single-tenant deployment"));
    }

    @Test
    void multiTenantModeBlocksUntilDatabaseRlsIsExplicitlyEnabled() {
        CacheService cacheService = mock(CacheService.class);
        when(cacheService.isRedisConnected()).thenReturn(true);

        MockEnvironment environment = baseEnvironment()
                .withProperty("app.distribution.require-tenant-isolation", "true")
                .withProperty("app.tenancy.mode", "MULTI_TENANT_RLS")
                .withProperty("app.tenancy.database-rls-enabled", "false")
                .withProperty("app.tenancy.require-tenant-header", "true")
                .withProperty("app.tenancy.bind-token-claims", "true")
                .withProperty("app.tenancy.require-token-tenant-claim", "true")
                .withProperty("app.tenancy.require-token-partner-claim", "true");

        DistributionReadinessService.ReadinessCheck check =
                new DistributionReadinessService(cacheService, environment, payoutGatewayProvider).evaluate().checks().stream()
                        .filter(candidate -> "tenant-isolation".equals(candidate.id()))
                        .findFirst()
                        .orElseThrow();

        assertEquals("FAIL", check.status());
        assertTrue(check.message().contains("MULTI_TENANT_RLS"));
    }

    @Test
    void payoutReadinessBlocksWhenConfigClaimsAdapterButGatewayBeanIsMissing() {
        CacheService cacheService = mock(CacheService.class);
        when(cacheService.isRedisConnected()).thenReturn(true);
        ObjectProvider<SellerPayoutTransferGateway> emptyProvider = mock(ObjectProvider.class);
        when(emptyProvider.getIfAvailable()).thenReturn(null);

        MockEnvironment environment = baseEnvironment()
                .withProperty("app.distribution.mode", "PRODUCTION")
                .withProperty("app.payout.transfer.provider", "BANK_EXTERNAL")
                .withProperty("app.payout.transfer.external-adapter-enabled", "true");

        DistributionReadinessService.ReadinessCheck check =
                new DistributionReadinessService(cacheService, environment, emptyProvider).evaluate().checks().stream()
                        .filter(candidate -> "seller-payout-transfer-provider".equals(candidate.id()))
                        .findFirst()
                        .orElseThrow();

        assertEquals("FAIL", check.status());
        assertTrue(check.message().contains("gateway bean"));
    }

    @Test
    void payoutReadinessPassesWhenRealAdapterNameMatchesConfiguredProvider() {
        CacheService cacheService = mock(CacheService.class);
        when(cacheService.isRedisConnected()).thenReturn(true);

        SellerPayoutTransferGateway realAdapter = mock(SellerPayoutTransferGateway.class);
        when(realAdapter.providerName()).thenReturn("TOSS_PAYOUTS");
        when(realAdapter.providerContractVerified()).thenReturn(true);
        ObjectProvider<SellerPayoutTransferGateway> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(realAdapter);

        MockEnvironment environment = baseEnvironment()
                .withProperty("app.distribution.mode", "PRODUCTION")
                .withProperty("app.payout.transfer.provider", "TOSS_PAYOUTS")
                .withProperty("app.payout.transfer.external-adapter-enabled", "true");

        DistributionReadinessService.ReadinessCheck check =
                new DistributionReadinessService(cacheService, environment, provider).evaluate().checks().stream()
                        .filter(candidate -> "seller-payout-transfer-provider".equals(candidate.id()))
                        .findFirst()
                        .orElseThrow();

        assertEquals("PASS", check.status());
        assertFalse(check.blocking());
    }

    @Test
    void payoutReadinessBlocksAdapterNotVerifiedAgainstProviderContract() {
        CacheService cacheService = mock(CacheService.class);
        when(cacheService.isRedisConnected()).thenReturn(true);

        // 현재 토스 지급대행 어댑터는 v2 공개 스펙과 맞지 않는다고 스스로 선언한다.
        SellerPayoutTransferGateway tossAdapter = new com.example.payment.infrastructure.gateway
                .TossPayoutsSellerPayoutTransferGateway(null, null, null);
        ObjectProvider<SellerPayoutTransferGateway> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(tossAdapter);

        MockEnvironment environment = baseEnvironment()
                .withProperty("app.distribution.mode", "PRODUCTION")
                .withProperty("app.payout.transfer.provider", "TOSS_PAYOUTS")
                .withProperty("app.payout.transfer.external-adapter-enabled", "true");

        DistributionReadinessService.ReadinessCheck check =
                new DistributionReadinessService(cacheService, environment, provider).evaluate().checks().stream()
                        .filter(candidate -> "seller-payout-transfer-provider".equals(candidate.id()))
                        .findFirst()
                        .orElseThrow();

        assertEquals("FAIL", check.status());
        assertTrue(check.blocking());
        assertTrue(check.message().contains("not verified"));
    }

    @Test
    void payoutReadinessBlocksWhileLedgerOnlyStubIsStillActive() {
        CacheService cacheService = mock(CacheService.class);
        when(cacheService.isRedisConnected()).thenReturn(true);

        SellerPayoutTransferGateway stub = mock(SellerPayoutTransferGateway.class);
        when(stub.providerName()).thenReturn("LEDGER_ONLY");
        ObjectProvider<SellerPayoutTransferGateway> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(stub);

        MockEnvironment environment = baseEnvironment()
                .withProperty("app.distribution.mode", "PRODUCTION")
                .withProperty("app.payout.transfer.provider", "LEDGER_ONLY")
                .withProperty("app.payout.transfer.external-adapter-enabled", "true");

        DistributionReadinessService.ReadinessCheck check =
                new DistributionReadinessService(cacheService, environment, provider).evaluate().checks().stream()
                        .filter(candidate -> "seller-payout-transfer-provider".equals(candidate.id()))
                        .findFirst()
                        .orElseThrow();

        assertEquals("FAIL", check.status());
        assertTrue(check.blocking());
    }

    private MockEnvironment baseEnvironment() {
        return new MockEnvironment()
                .withProperty("app.distribution.brand-name", "EverySale")
                .withProperty("app.distribution.release-channel", "test")
                .withProperty("app.distribution.minimum-java-version", "17")
                .withProperty("app.temporal.enabled", "true")
                .withProperty("app.temporal.worker-enabled", "true")
                .withProperty("app.outbox.enabled", "true")
                .withProperty("app.inventory.reconciliation.enabled", "true")
                .withProperty("payment.toss.reconciliation.enabled", "true")
                .withProperty("app.auction.auto-close-enabled", "true")
                .withProperty("app.marketplace.realtime.redis-broadcast-enabled", "true")
                .withProperty("app.payout.transfer.provider", "TEST_EXTERNAL")
                .withProperty("app.payout.transfer.external-adapter-enabled", "true")
                .withProperty("app.payout.transfer.reconciliation.enabled", "true")
                .withProperty("spring.flyway.enabled", "true")
                .withProperty("spring.jpa.hibernate.ddl-auto", "validate")
                .withProperty("app.legacy-wal.enabled", "false")
                .withProperty("app.checkout.public-complete-enabled", "false")
                .withProperty("app.checkout.legacy-reservation-api-enabled", "false")
                .withProperty("payment.allow-gateway-fallback", "false")
                .withProperty("app.audit.enabled", "true")
                .withProperty("app.media.storage-provider", "S3")
                .withProperty("app.media.validate-on-startup", "true")
                .withProperty("app.media.s3.bucket", "everysale-private-media")
                .withProperty("app.media.s3.server-side-encryption", "AES256")
                .withProperty("app.media.malware-scan.provider", "CLAMAV")
                .withProperty("app.media.malware-scan.validate-on-startup", "true")
                .withProperty("app.media.malware-scan.clamav.host", "clamav.example.com")
                .withProperty("app.media.malware-scan.clamav.port", "3310")
                .withProperty("spring.datasource.url", "jdbc:postgresql://db.example.com:5432/payment")
                .withProperty("spring.data.redis.host", "redis.example.com")
                .withProperty("spring.kafka.bootstrap-servers", "kafka-1.example.com:9092,kafka-2.example.com:9092")
                .withProperty("app.temporal.target", "temporal.example.com:7233")
                .withProperty("app.security.cors.allowed-origins", "https://app.example.com,https://admin.example.com")
                .withProperty("app.security.external-auth.enabled", "false")
                .withProperty("app.security.external-auth.frontend.authorization-uri", "https://identity.example.com/authorize")
                .withProperty("app.security.external-auth.frontend.token-uri", "https://identity.example.com/token")
                .withProperty("app.security.external-auth.frontend.client-id", "everysale-web")
                .withProperty("app.security.external-auth.frontend.scopes", "openid,profile,email")
                .withProperty("app.tenancy.require-tenant-header", "true");
    }
}

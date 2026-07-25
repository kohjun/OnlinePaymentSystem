package com.example.payment;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
public abstract class TestcontainersIntegrationSupport {

    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("everysale_integration")
            .withUsername("everysale")
            .withPassword("integration-test-only");

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> {
            ensureContainersStarted();
            return REDIS.getHost();
        });
        registry.add("spring.data.redis.port", () -> {
            ensureContainersStarted();
            return REDIS.getMappedPort(6379);
        });
        registry.add("spring.datasource.url", () -> {
            ensureContainersStarted();
            return POSTGRES.getJdbcUrl();
        });
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    private static synchronized void ensureContainersStarted() {
        if (!REDIS.isRunning()) {
            REDIS.start();
        }
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
    }
}

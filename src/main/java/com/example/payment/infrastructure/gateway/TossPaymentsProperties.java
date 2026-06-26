package com.example.payment.infrastructure.gateway;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "payment.toss")
public class TossPaymentsProperties {
    private String clientKey;
    private String secretKey;
    private String baseUrl = "https://api.tosspayments.com";
    private String mode = "test";
    private String apiVersion = "2022-11-16";
    private String testCode;
    private Webhook webhook = new Webhook();
    private Reconciliation reconciliation = new Reconciliation();

    @Data
    public static class Webhook {
        private boolean enabled = false;
        private String pathToken;
        private long retryFixedDelayMs = 30000;
        private int maxRetry = 7;
    }

    @Data
    public static class Reconciliation {
        private boolean enabled = true;
        private long fixedDelayMs = 60000;
        private long staleSeconds = 300;
    }
}

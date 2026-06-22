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
}

package com.example.payment.infrastructure.gateway;

import com.example.payment.application.dto.PaymentGatewayResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("toss-sandbox")
class TossPaymentsSandboxTest {

    @Test
    void testSecretCanReachTossQueryApiWithoutCreatingPayment() {
        String clientKey = requiredEnvironment("TOSS_CLIENT_KEY");
        String secretKey = requiredEnvironment("TOSS_SECRET_KEY");
        assertTrue(clientKey.startsWith("test_"), "Sandbox test requires a Toss test client key.");
        assertTrue(secretKey.startsWith("test_"), "Sandbox test requires a Toss test secret key.");

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);

        TossPaymentsProperties properties = new TossPaymentsProperties();
        properties.setClientKey(clientKey);
        properties.setSecretKey(secretKey);
        properties.setMode("test");
        properties.setBaseUrl("https://api.tosspayments.com");

        TossPaymentsGateway gateway = new TossPaymentsGateway(
                new RestTemplate(factory),
                new ObjectMapper(),
                properties
        );
        PaymentGatewayResult result = gateway.getPaymentStatusByPaymentId(
                "EVERYSALE-PREFLIGHT-" + UUID.randomUUID().toString().replace("-", "")
        );

        assertFalse(result.isSuccess());
        assertNotEquals("UNKNOWN", result.getGatewayStatus(), "Toss sandbox API could not be reached.");
        assertNotEquals("TOSS_HTTP_401", result.getErrorCode(), "Toss sandbox secret key was rejected.");
        assertNotEquals("UNAUTHORIZED_KEY", result.getErrorCode(), "Toss sandbox secret key was rejected.");
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for tossSandboxTest.");
        }
        return value.trim();
    }
}

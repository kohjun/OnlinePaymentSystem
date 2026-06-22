package com.example.payment.infrastructure.gateway;

import com.example.payment.application.dto.PaymentGatewayRequest;
import com.example.payment.application.dto.PaymentGatewayResult;
import com.example.payment.domain.service.PaymentGatewayService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class TossPaymentsGateway implements PaymentGatewayService {

    public static final String GATEWAY_NAME = "TOSS_PAYMENTS";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final TossPaymentsProperties properties;

    @Override
    public PaymentGatewayResult processPayment(PaymentGatewayRequest request) {
        return authorize(request);
    }

    @Override
    public PaymentGatewayResult authorize(PaymentGatewayRequest request) {
        requireSecretKey();
        requireText(request.getTossPaymentKey(), "Toss paymentKey is required");
        requireText(request.getTossOrderId(), "Toss orderId is required");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("paymentKey", request.getTossPaymentKey());
        body.put("orderId", request.getTossOrderId());
        body.put("amount", request.getAmount());

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url("/v1/payments/confirm"),
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers(request.getIdempotencyKey())),
                    String.class
            );
            return toResult(parse(response.getBody()));
        } catch (HttpStatusCodeException e) {
            return errorResult(e);
        }
    }

    @Override
    public boolean refundPayment(String transactionId) {
        requireSecretKey();
        requireText(transactionId, "Toss paymentKey is required for refund");

        Map<String, Object> body = Map.of("cancelReason", "EverySale refund request");
        try {
            restTemplate.exchange(
                    url("/v1/payments/" + transactionId + "/cancel"),
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers("refund-" + transactionId)),
                    String.class
            );
            return true;
        } catch (HttpStatusCodeException e) {
            log.warn("Toss refund failed: paymentKey={}, status={}, body={}",
                    mask(transactionId), e.getStatusCode(), safeBody(e.getResponseBodyAsString()));
            return false;
        }
    }

    @Override
    public PaymentGatewayResult getPaymentStatus(String transactionId) {
        requireSecretKey();
        requireText(transactionId, "Toss paymentKey is required");
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url("/v1/payments/" + transactionId),
                    HttpMethod.GET,
                    new HttpEntity<>(headers(null)),
                    String.class
            );
            return toResult(parse(response.getBody()));
        } catch (HttpStatusCodeException e) {
            return errorResult(e);
        }
    }

    @Override
    public PaymentGatewayResult getPaymentStatusByPaymentId(String paymentId) {
        requireSecretKey();
        requireText(paymentId, "Toss orderId is required");
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url("/v1/payments/orders/" + paymentId),
                    HttpMethod.GET,
                    new HttpEntity<>(headers(null)),
                    String.class
            );
            return toResult(parse(response.getBody()));
        } catch (HttpStatusCodeException e) {
            return errorResult(e);
        }
    }

    @Override
    public String getGatewayName() {
        return GATEWAY_NAME;
    }

    @Override
    public boolean isHealthy() {
        return hasText(properties.getClientKey())
                && hasText(properties.getSecretKey())
                && hasText(properties.getBaseUrl());
    }

    @Override
    public boolean supports(String paymentMethod) {
        if (paymentMethod == null) {
            return false;
        }
        return switch (paymentMethod.toUpperCase().trim()) {
            case "CREDIT_CARD", "DEBIT_CARD", "TOSS_PAY", "BANK_TRANSFER", "MOBILE_PAY", "MOCK", "TEST" -> true;
            default -> false;
        };
    }

    private PaymentGatewayResult toResult(JsonNode payment) {
        String status = text(payment, "status");
        boolean approved = "DONE".equals(status);
        String paymentKey = text(payment, "paymentKey");
        BigDecimal amount = payment.hasNonNull("totalAmount")
                ? payment.get("totalAmount").decimalValue()
                : BigDecimal.ZERO;
        String approvalNumber = text(payment.path("card"), "approveNo");
        if (!hasText(approvalNumber)) {
            approvalNumber = text(payment, "lastTransactionKey");
        }

        return PaymentGatewayResult.builder()
                .success(approved)
                .gatewayStatus(mapStatus(status))
                .transactionId(paymentKey)
                .approvalNumber(approvalNumber)
                .processedAmount(amount)
                .currency(text(payment, "currency"))
                .processedAt(parseTime(text(payment, "approvedAt")))
                .gatewayName(getGatewayName())
                .errorCode(approved ? null : status)
                .errorMessage(approved ? null : "Toss payment status is " + status)
                .build();
    }

    private String mapStatus(String tossStatus) {
        if ("DONE".equals(tossStatus)) {
            return "APPROVED";
        }
        if ("CANCELED".equals(tossStatus)) {
            return "REFUNDED";
        }
        if ("PARTIAL_CANCELED".equals(tossStatus)) {
            return "PARTIALLY_REFUNDED";
        }
        if ("ABORTED".equals(tossStatus) || "EXPIRED".equals(tossStatus)) {
            return "FAILED";
        }
        return "UNKNOWN";
    }

    private PaymentGatewayResult errorResult(HttpStatusCodeException e) {
        String code = "TOSS_HTTP_" + e.getStatusCode().value();
        String message = e.getResponseBodyAsString();
        try {
            JsonNode error = parse(message);
            code = text(error, "code");
            message = text(error, "message");
        } catch (Exception ignored) {
        }
        return PaymentGatewayResult.builder()
                .success(false)
                .gatewayStatus("FAILED")
                .errorCode(code)
                .errorMessage(message)
                .gatewayName(getGatewayName())
                .processedAt(LocalDateTime.now())
                .build();
    }

    private HttpHeaders headers(String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBasicAuth(Base64.getEncoder().encodeToString((properties.getSecretKey() + ":")
                .getBytes(StandardCharsets.UTF_8)));
        if (hasText(idempotencyKey)) {
            headers.add("Idempotency-Key", idempotencyKey);
        }
        if (hasText(properties.getTestCode()) && !"live".equalsIgnoreCase(properties.getMode())) {
            headers.add("TossPayments-Test-Code", properties.getTestCode());
        }
        return headers;
    }

    private String url(String path) {
        return properties.getBaseUrl().replaceAll("/+$", "") + path;
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body == null ? "{}" : body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Toss response", e);
        }
    }

    private LocalDateTime parseTime(String value) {
        if (!hasText(value)) {
            return LocalDateTime.now();
        }
        try {
            return OffsetDateTime.parse(value).toLocalDateTime();
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private void requireSecretKey() {
        requireText(properties.getSecretKey(), "Toss secret key is not configured");
    }

    private void requireText(String value, String message) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String mask(String value) {
        if (!hasText(value) || value.length() <= 10) {
            return "****";
        }
        return value.substring(0, 6) + "****" + value.substring(value.length() - 4);
    }

    private String safeBody(String body) {
        return body == null ? "" : body.replaceAll("(?i)authorization[^,}]*", "authorization=****");
    }
}

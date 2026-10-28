package com.example.payment.infrastructure.gateway;

import com.example.payment.application.dto.PaymentGatewayRequest;
import com.example.payment.application.dto.PaymentGatewayResult;
import com.example.payment.application.dto.PaymentRefundRequest;
import com.example.payment.domain.exception.PaymentGatewayResultUnknownException;
import com.example.payment.domain.service.PaymentGatewayService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
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
            return gatewayErrorResult(e);
        } catch (RestClientException e) {
            return unknownResult("TOSS_NETWORK_ERROR", e.getMessage());
        }
    }

    @Override
    public boolean refundPayment(String transactionId) {
        return refundPayment(PaymentRefundRequest.builder()
                .transactionId(transactionId)
                .idempotencyKey("refund-" + transactionId)
                .reason("EverySale refund request")
                .build());
    }

    @Override
    public boolean refundPayment(PaymentRefundRequest request) {
        var result = refund(request);
        if (result.status() == com.example.payment.application.dto.PaymentRefundResult.Status.UNKNOWN) {
            throw new PaymentGatewayResultUnknownException(result.message(), null);
        }
        return result.status() == com.example.payment.application.dto.PaymentRefundResult.Status.SUCCEEDED;
    }

    @Override
    public com.example.payment.application.dto.PaymentRefundResult refund(PaymentRefundRequest request) {
        requireSecretKey();
        requireText(request.getTransactionId(), "Toss paymentKey is required for refund");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cancelReason", hasText(request.getReason()) ? request.getReason() : "EverySale refund");
        if (request.getAmount() != null) body.put("cancelAmount", request.getAmount());
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url("/v1/payments/" + request.getTransactionId() + "/cancel"), HttpMethod.POST,
                    new HttpEntity<>(body, headers(request.getIdempotencyKey())), String.class);
            PaymentGatewayResult payment = toResult(parse(response.getBody()));
            boolean cancelled = "REFUNDED".equals(payment.getGatewayStatus()) || "PARTIALLY_REFUNDED".equals(payment.getGatewayStatus());
            return new com.example.payment.application.dto.PaymentRefundResult(
                    cancelled ? com.example.payment.application.dto.PaymentRefundResult.Status.SUCCEEDED
                            : com.example.payment.application.dto.PaymentRefundResult.Status.UNKNOWN,
                    payment, cancelled ? null : "Provider cancellation not confirmed");
        } catch (HttpStatusCodeException failure) {
            PaymentGatewayResult observation = gatewayErrorResult(failure);
            return new com.example.payment.application.dto.PaymentRefundResult(
                    "FAILED".equals(observation.getGatewayStatus())
                            ? com.example.payment.application.dto.PaymentRefundResult.Status.FAILED
                            : com.example.payment.application.dto.PaymentRefundResult.Status.UNKNOWN, null,
                    "Provider cancellation returned HTTP " + failure.getStatusCode().value());
        } catch (RuntimeException failure) {
            return com.example.payment.application.dto.PaymentRefundResult.unknown("Provider cancellation response unavailable");
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
            return unknownResult("TOSS_LOOKUP_UNAVAILABLE", "Provider lookup returned HTTP " + e.getStatusCode().value());
        } catch (RestClientException e) {
            return unknownResult("TOSS_NETWORK_ERROR", e.getMessage());
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
            return unknownResult("TOSS_LOOKUP_UNAVAILABLE", "Provider lookup returned HTTP " + e.getStatusCode().value());
        } catch (RestClientException e) {
            return unknownResult("TOSS_NETWORK_ERROR", e.getMessage());
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
            case "CREDIT_CARD", "DEBIT_CARD", "TOSS_PAY", "BANK_TRANSFER", "MOBILE_PAY" -> true;
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
                .orderId(text(payment, "orderId"))
                .balanceAmount(payment.hasNonNull("balanceAmount") ? payment.get("balanceAmount").decimalValue() : null)
                .cancellations(cancellations(payment))
                .approvalNumber(approvalNumber)
                .processedAmount(amount)
                .currency(text(payment, "currency"))
                .processedAt(parseTime(text(payment, "approvedAt")))
                .gatewayName(getGatewayName())
                .errorCode(approved ? null : status)
                .errorMessage(approved ? null : "Toss payment status is " + status)
                .build();
    }

    private java.util.List<PaymentGatewayResult.ProviderCancellation> cancellations(JsonNode payment) {
        java.util.List<PaymentGatewayResult.ProviderCancellation> result = new java.util.ArrayList<>();
        for (JsonNode cancel : payment.path("cancels")) {
            if (cancel.hasNonNull("transactionKey") && cancel.hasNonNull("cancelAmount")) {
                result.add(new PaymentGatewayResult.ProviderCancellation(text(cancel, "transactionKey"),
                        cancel.get("cancelAmount").decimalValue(), text(cancel, "cancelStatus")));
            }
        }
        return result;
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

    private PaymentGatewayResult gatewayErrorResult(HttpStatusCodeException e) {
        if (e.getStatusCode().is5xxServerError()
                || e.getStatusCode().value() == 408
                || e.getStatusCode().value() == 429
                || e.getStatusCode().value() == 409) {
            return unknownResult("TOSS_HTTP_" + e.getStatusCode().value(), e.getResponseBodyAsString());
        }
        String code = "TOSS_HTTP_" + e.getStatusCode().value();
        String message = e.getResponseBodyAsString();
        try {
            JsonNode error = parse(message);
            code = text(error, "code");
            message = text(error, "message");
        } catch (Exception ignored) {
        }
        // HTTP 400 also covers already-processed and provider-in-progress responses.
        // Only an explicit financial rejection can make inventory releasable.
        if (code == null || !java.util.Set.of("REJECT_CARD_COMPANY", "REJECT_CARD_PAYMENT", "REJECT_ACCOUNT_PAYMENT",
                "INVALID_REJECT_CARD", "INVALID_STOPPED_CARD", "INVALID_CARD_LOST_OR_STOLEN",
                "REFUND_REJECTED", "FAILED_CLOSED_ACCOUNT_REFUND", "FORBIDDEN_BANK_REFUND_REQUEST",
                "NOT_CANCELABLE_PAYMENT", "EXCEED_MAX_REFUND_DUE").contains(code)) {
            return unknownResult(code, "Provider result requires status verification");
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

    private PaymentGatewayResult unknownResult(String code, String message) {
        return PaymentGatewayResult.builder()
                .success(false)
                .gatewayStatus("UNKNOWN")
                .errorCode(code)
                .errorMessage(safeMessage(message))
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
            // Toss는 +09:00 오프셋으로 보낸다. 벽시계 값만 떼면 9시간 어긋난 UTC가 된다.
            return OffsetDateTime.parse(value).withOffsetSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime();
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asString("");
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

    private String safeMessage(String message) {
        if (!hasText(message)) {
            return "Toss response could not be determined.";
        }
        return safeBody(message).replaceAll("(?i)(paymentKey[=: ]+)[^, }]+", "$1****");
    }
}

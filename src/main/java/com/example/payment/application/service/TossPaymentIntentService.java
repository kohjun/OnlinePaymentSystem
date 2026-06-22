package com.example.payment.application.service;

import com.example.payment.domain.entity.TossPaymentIntent;
import com.example.payment.domain.repository.TossPaymentIntentRepository;
import com.example.payment.infrastructure.gateway.TossPaymentsProperties;
import com.example.payment.infrastructure.util.IdGenerator;
import com.example.payment.presentation.dto.request.CompleteReservationRequest;
import com.example.payment.presentation.dto.request.TossPaymentConfirmRequest;
import com.example.payment.presentation.dto.response.CompleteReservationResponse;
import com.example.payment.presentation.dto.response.TossPaymentIntentResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TossPaymentIntentService {

    private static final int INTENT_TTL_MINUTES = 10;

    private final TossPaymentIntentRepository repository;
    private final CheckoutPricingService checkoutPricingService;
    private final CompleteReservationGateway completeReservationGateway;
    private final TossPaymentsProperties tossProperties;
    private final ObjectMapper objectMapper;

    @Transactional
    public TossPaymentIntentResponse createIntent(CompleteReservationRequest request) {
        checkoutPricingService.applyProductPrice(request, true);
        String requestHash = requestHash(request);

        TossPaymentIntent existing = repository.findByIdempotencyKey(request.getIdempotencyKey()).orElse(null);
        if (existing != null) {
            if (!existing.getRequestHash().equals(requestHash)) {
                throw new IdempotencyConflictException("IDEMPOTENCY_KEY_CONFLICT");
            }
            return toResponse(existing);
        }

        String intentId = "TOSS-INTENT-" + IdGenerator.generateEventId();
        TossPaymentIntent intent = TossPaymentIntent.builder()
                .intentId(intentId)
                .orderId(IdGenerator.generateOrderId())
                .idempotencyKey(request.getIdempotencyKey())
                .requestHash(requestHash)
                .customerId(request.getCustomerId())
                .productId(request.getProductId())
                .quantity(request.getQuantity())
                .amount(request.getPaymentInfo().getAmount())
                .currency(request.getPaymentInfo().getCurrency())
                .paymentMethod(defaultText(request.getPaymentInfo().getPaymentMethod(), "CREDIT_CARD"))
                .orderName(orderName(request))
                .customerKey(customerKey(request.getCustomerId()))
                .merchantId(request.getPaymentInfo().getMerchantId())
                .clientId(request.getClientId())
                .seatId(request.getSeatId())
                .status("READY")
                .successUrl(successUrl(request, intentId))
                .failUrl(failUrl(request, intentId))
                .expiresAt(LocalDateTime.now().plusMinutes(INTENT_TTL_MINUTES))
                .createdAt(LocalDateTime.now())
                .build();
        try {
            return toResponse(repository.save(intent));
        } catch (DataIntegrityViolationException race) {
            TossPaymentIntent raced = repository.findByIdempotencyKey(request.getIdempotencyKey())
                    .orElseThrow(() -> race);
            if (!raced.getRequestHash().equals(requestHash)) {
                throw new IdempotencyConflictException("IDEMPOTENCY_KEY_CONFLICT");
            }
            return toResponse(raced);
        }
    }

    @Transactional
    public CompleteReservationResponse confirm(TossPaymentConfirmRequest request) {
        TossPaymentIntent intent = repository.findById(request.getIntentId())
                .orElseThrow(() -> new IllegalArgumentException("TOSS_PAYMENT_INTENT_NOT_FOUND"));

        validateConfirm(request, intent);

        if (intent.getResponseBody() != null && !intent.getResponseBody().isBlank()) {
            return readResponse(intent.getResponseBody());
        }

        intent.setPaymentKey(request.getPaymentKey());
        intent.setStatus("AUTHENTICATED");
        repository.save(intent);

        CompleteReservationRequest completeRequest = toCompleteReservationRequest(intent);
        CompleteReservationResponse response = completeReservationGateway.processCompleteReservation(completeRequest);

        intent.setWorkflowId(response.getWorkflowId());
        intent.setStatus(response.getStatus());
        try {
            intent.setResponseBody(objectMapper.writeValueAsString(response));
        } catch (Exception e) {
            intent.setResponseBody(null);
        }
        repository.save(intent);
        return response;
    }

    private void validateConfirm(TossPaymentConfirmRequest request, TossPaymentIntent intent) {
        if (!intent.getOrderId().equals(request.getOrderId())) {
            throw new IllegalArgumentException("TOSS_PAYMENT_CONFLICT");
        }
        if (intent.getPaymentKey() != null && !intent.getPaymentKey().equals(request.getPaymentKey())) {
            throw new IllegalArgumentException("TOSS_PAYMENT_CONFLICT");
        }
        if (LocalDateTime.now().isAfter(intent.getExpiresAt())) {
            throw new IllegalArgumentException("TOSS_PAYMENT_INTENT_EXPIRED");
        }
        if (money(intent.getAmount()).compareTo(money(request.getAmount())) != 0) {
            throw new AmountMismatchException("AMOUNT_MISMATCH: expected " + intent.getAmount() + " but Toss returned " + request.getAmount());
        }
    }

    private CompleteReservationRequest toCompleteReservationRequest(TossPaymentIntent intent) {
        return CompleteReservationRequest.builder()
                .productId(intent.getProductId())
                .customerId(intent.getCustomerId())
                .quantity(intent.getQuantity())
                .clientId(intent.getClientId())
                .seatId(intent.getSeatId())
                .idempotencyKey(intent.getIdempotencyKey())
                .correlationId("TOSS-" + intent.getIntentId())
                .paymentInfo(CompleteReservationRequest.PaymentInfo.builder()
                        .amount(intent.getAmount())
                        .currency(intent.getCurrency())
                        .paymentMethod(intent.getPaymentMethod())
                        .merchantId(intent.getMerchantId())
                        .orderName(intent.getOrderName())
                        .tossPaymentKey(intent.getPaymentKey())
                        .tossOrderId(intent.getOrderId())
                        .tossIntentId(intent.getIntentId())
                        .build())
                .build();
    }

    private TossPaymentIntentResponse toResponse(TossPaymentIntent intent) {
        return TossPaymentIntentResponse.builder()
                .intentId(intent.getIntentId())
                .orderId(intent.getOrderId())
                .orderName(intent.getOrderName())
                .amount(intent.getAmount())
                .currency(intent.getCurrency())
                .customerKey(intent.getCustomerKey())
                .clientKey(tossProperties.getClientKey())
                .successUrl(intent.getSuccessUrl())
                .failUrl(intent.getFailUrl())
                .status(intent.getStatus())
                .expiresAt(intent.getExpiresAt())
                .build();
    }

    private CompleteReservationResponse readResponse(String responseBody) {
        try {
            return objectMapper.readValue(responseBody, CompleteReservationResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("Stored Toss payment response is invalid", e);
        }
    }

    private String requestHash(CompleteReservationRequest request) {
        try {
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("productId", request.getProductId());
            canonical.put("customerId", request.getCustomerId());
            canonical.put("quantity", request.getQuantity());
            canonical.put("clientId", request.getClientId());
            canonical.put("seatId", request.getSeatId());
            canonical.put("amount", request.getPaymentInfo().getAmount());
            canonical.put("currency", request.getPaymentInfo().getCurrency());
            canonical.put("paymentMethod", request.getPaymentInfo().getPaymentMethod());
            canonical.put("merchantId", request.getPaymentInfo().getMerchantId());
            byte[] json = objectMapper.writeValueAsBytes(canonical);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash Toss payment intent request", e);
        }
    }

    private String orderName(CompleteReservationRequest request) {
        String requested = request.getPaymentInfo().getOrderName();
        if (requested != null && !requested.isBlank()) {
            return requested.length() > 100 ? requested.substring(0, 100) : requested;
        }
        String fallback = "EverySale " + request.getProductId() + " x " + request.getQuantity();
        return fallback.length() > 100 ? fallback.substring(0, 100) : fallback;
    }

    private String successUrl(CompleteReservationRequest request, String intentId) {
        String value = request.getPaymentInfo().getSuccessUrl();
        return withIntentId(defaultText(value, "/index.html?tossResult=success"), intentId);
    }

    private String failUrl(CompleteReservationRequest request, String intentId) {
        String value = request.getPaymentInfo().getFailUrl();
        return withIntentId(defaultText(value, "/index.html?tossResult=fail"), intentId);
    }

    private String withIntentId(String url, String intentId) {
        if (url.contains("intentId=")) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + "intentId=" + intentId;
    }

    private String customerKey(String customerId) {
        String normalized = defaultText(customerId, "anonymous").replaceAll("[^A-Za-z0-9_-]", "_");
        return normalized.length() > 50 ? normalized.substring(0, 50) : normalized;
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}

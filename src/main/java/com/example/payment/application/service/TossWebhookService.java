package com.example.payment.application.service;

import com.example.payment.application.dto.PaymentGatewayResult;
import com.example.payment.domain.entity.PaymentRecord;
import com.example.payment.domain.entity.TossWebhookEvent;
import com.example.payment.domain.repository.PaymentRecordRepository;
import com.example.payment.domain.repository.TossWebhookEventRepository;
import com.example.payment.infrastructure.gateway.TossPaymentsGateway;
import com.example.payment.infrastructure.gateway.TossPaymentsProperties;
import com.example.payment.infrastructure.util.IdGenerator;
import com.example.payment.presentation.dto.response.CompleteReservationResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class TossWebhookService {

    private static final Set<String> SUPPORTED_EVENTS = Set.of(
            "PAYMENT_STATUS_CHANGED",
            "CANCEL_STATUS_CHANGED"
    );

    private final TossWebhookEventRepository webhookEventRepository;
    private final PaymentRecordRepository paymentRecordRepository;
    private final TossPaymentIntentService tossPaymentIntentService;
    private final MarketplaceOrderService marketplaceOrderService;
    private final TossPaymentsGateway tossPaymentsGateway;
    private final TossPaymentsProperties tossProperties;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final RefundLedgerWriter refunds;
    private final RefundRecoveryService refundRecovery;

    @Transactional
    public WebhookReceipt receive(String token, String rawPayload) {
        if (!tossProperties.getWebhook().isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Toss webhook is disabled.");
        }
        if (!hasText(tossProperties.getWebhook().getPathToken())
                || !tossProperties.getWebhook().getPathToken().equals(token)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Toss webhook endpoint was not found.");
        }

        JsonNode root = parse(rawPayload);
        JsonNode data = root.has("data") && root.get("data").isObject() ? root.get("data") : root;
        String eventType = defaultText(firstText(root, "eventType", "eventName", "type"), "UNKNOWN");
        String paymentKey = firstText(data, "paymentKey");
        String orderId = firstText(data, "orderId");
        String paymentStatus = firstText(data, "status", "paymentStatus");
        String dedupeKey = dedupeKey(root, data, eventType, paymentKey, orderId, paymentStatus, rawPayload);

        TossWebhookEvent event = webhookEventRepository.findByDedupeKey(dedupeKey).orElse(null);
        if (event == null) {
            event = webhookEventRepository.save(TossWebhookEvent.builder()
                    .eventId("TOSS-WH-" + IdGenerator.generateEventId())
                    .dedupeKey(dedupeKey)
                    .eventType(eventType)
                    .paymentKey(paymentKey)
                    .orderId(orderId)
                    .paymentStatus(paymentStatus)
                    .rawPayload(rawPayload)
                    .processingStatus("PENDING")
                    .attemptCount(0)
                    .receivedAt(LocalDateTime.now())
                    .build());
            increment("everysale.toss.webhook.received", eventType);
        }

        return new WebhookReceipt(event.getEventId(), event.getDedupeKey(), event.getProcessingStatus());
    }

    public int processPendingEvents() {
        webhookEventRepository.recoverStale(LocalDateTime.now());
        List<TossWebhookEvent> events = webhookEventRepository
                .findByProcessingStatusInAndAttemptCountLessThanOrderByReceivedAtAsc(
                        List.of("PENDING", "FAILED"),
                        tossProperties.getWebhook().getMaxRetry(),
                        PageRequest.of(0, 50)
                );
        int processed = 0;
        for (TossWebhookEvent event : events) {
            processEvent(event);
            processed++;
        }
        return processed;
    }

    public void processEvent(TossWebhookEvent event) {
        String token = java.util.UUID.randomUUID().toString();
        if (webhookEventRepository.claim(event.getEventId(), token, LocalDateTime.now().plusMinutes(2)) != 1) return;
        try {
            if (SUPPORTED_EVENTS.contains(event.getEventType())) {
                PaymentGatewayResult observed = hasText(event.getPaymentKey())
                        ? tossPaymentsGateway.getPaymentStatus(event.getPaymentKey())
                        : tossPaymentsGateway.getPaymentStatusByPaymentId(event.getOrderId());
                if (observed == null || observed.observation() == PaymentGatewayResult.Observation.UNKNOWN) {
                    throw new IllegalStateException("Provider status is not confirmed");
                }
                if ((hasText(event.getPaymentKey()) && !event.getPaymentKey().equals(observed.getTransactionId()))
                        || (hasText(event.getOrderId()) && !event.getOrderId().equals(observed.getOrderId()))) {
                    throw new IllegalStateException("Provider webhook reference mismatch");
                }
                if (observed.observation() == PaymentGatewayResult.Observation.REFUNDED
                        || observed.observation() == PaymentGatewayResult.Observation.PARTIALLY_REFUNDED) {
                    PaymentRecord payment = paymentRecordRepository.findByTransactionId(observed.getTransactionId())
                            .orElseThrow(() -> new IllegalStateException("Local payment recovery required"));
                    refunds.observe(payment.getPaymentId(), observed, null, false);
                    refundRecovery.recoverPayment(payment.getPaymentId());
                } else {
                    tossPaymentIntentService.recoverIntentByProviderReference(observed.getTransactionId(), observed.getOrderId());
                }
            }
            webhookEventRepository.finish(event.getEventId(), token, "SUCCEEDED", null, LocalDateTime.now());
            increment("everysale.toss.webhook.processed", event.getEventType());
        } catch (Exception failure) {
            webhookEventRepository.finish(event.getEventId(), token, "FAILED", "Provider recovery requires verification", LocalDateTime.now());
            increment("everysale.toss.webhook.failed", event.getEventType());
            log.warn("Webhook recovery deferred: eventId={}", event.getEventId());
        }
    }

    private JsonNode parse(String rawPayload) {
        try {
            return objectMapper.readTree(rawPayload == null ? "{}" : rawPayload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid Toss webhook payload", e);
        }
    }

    private String dedupeKey(JsonNode root,
                             JsonNode data,
                             String eventType,
                             String paymentKey,
                             String orderId,
                             String paymentStatus,
                             String rawPayload) {
        String providerEventId = firstText(root, "eventId", "id", "webhookEventId");
        if (hasText(providerEventId)) {
            return eventType + ":" + providerEventId;
        }
        String transactionKey = firstText(data, "lastTransactionKey", "transactionKey", "cancelId");
        if (hasText(paymentKey) || hasText(orderId)) {
            return String.join(":",
                    eventType,
                    defaultText(paymentKey, "-"),
                    defaultText(orderId, "-"),
                    defaultText(paymentStatus, "-"),
                    defaultText(transactionKey, "-")
            );
        }
        return eventType + ":" + sha256(rawPayload);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(defaultText(value, "").getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash Toss webhook payload", e);
        }
    }

    private String firstText(JsonNode node, String... fields) {
        if (node == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            // Jackson 3의 asString()은 객체·배열 노드에서 예외를 던진다. 예전처럼 빈 값으로 본다.
            if (value != null && !value.isNull() && !value.asString("").isBlank()) {
                return value.asString("");
            }
        }
        return null;
    }

    private void increment(String name, String eventType) {
        meterRegistry.counter(name, "eventType", defaultText(eventType, "UNKNOWN")).increment();
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public record WebhookReceipt(String eventId, String dedupeKey, String processingStatus) {
    }
}

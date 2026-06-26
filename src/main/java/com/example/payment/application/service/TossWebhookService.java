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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final TossPaymentsGateway tossPaymentsGateway;
    private final TossPaymentsProperties tossProperties;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

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

    @Transactional
    public int processPendingEvents() {
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

    @Transactional
    public void processEvent(TossWebhookEvent event) {
        event.setAttemptCount(event.getAttemptCount() + 1);
        event.setProcessingStatus("PROCESSING");
        event.setFailureReason(null);
        webhookEventRepository.save(event);

        try {
            if (!SUPPORTED_EVENTS.contains(event.getEventType())) {
                markSucceeded(event, "Unsupported event ignored: " + event.getEventType());
                return;
            }

            JsonNode root = parse(event.getRawPayload());
            JsonNode data = root.has("data") && root.get("data").isObject() ? root.get("data") : root;
            String paymentKey = defaultText(event.getPaymentKey(), firstText(data, "paymentKey"));
            String orderId = defaultText(event.getOrderId(), firstText(data, "orderId"));
            String status = defaultText(event.getPaymentStatus(), firstText(data, "status", "paymentStatus"));

            if (!hasText(status) && hasText(paymentKey)) {
                PaymentGatewayResult gatewayResult = tossPaymentsGateway.getPaymentStatus(paymentKey);
                status = gatewayResult.getGatewayStatus();
            }
            event.setPaymentKey(paymentKey);
            event.setOrderId(orderId);
            event.setPaymentStatus(status);

            if ("PAYMENT_STATUS_CHANGED".equals(event.getEventType())) {
                handlePaymentStatusChanged(paymentKey, orderId, status);
                markSucceeded(event, null);
                return;
            }
            if ("CANCEL_STATUS_CHANGED".equals(event.getEventType())) {
                handleCancelStatusChanged(paymentKey, orderId, status);
                markSucceeded(event, null);
                return;
            }
        } catch (Exception e) {
            event.setProcessingStatus("FAILED");
            event.setFailureReason(e.getMessage());
            webhookEventRepository.save(event);
            increment("everysale.toss.webhook.failed", event.getEventType());
            log.warn("Toss webhook processing failed: eventId={}, eventType={}, reason={}",
                    event.getEventId(), event.getEventType(), e.getMessage());
        }
    }

    private void handlePaymentStatusChanged(String paymentKey, String orderId, String status) {
        if ("DONE".equals(status) || "APPROVED".equals(status)) {
            CompleteReservationResponse response = tossPaymentIntentService
                    .recoverIntentByProviderReference(paymentKey, orderId);
            if ("FAILED".equals(response.getStatus())) {
                throw new IllegalStateException("Toss payment recovery failed: " + response.getMessage());
            }
            return;
        }
        if ("ABORTED".equals(status) || "EXPIRED".equals(status) || "FAILED".equals(status)) {
            tossPaymentIntentService.markProviderTerminalStatus(paymentKey, orderId, status);
        }
    }

    private void handleCancelStatusChanged(String paymentKey, String orderId, String status) {
        String mappedStatus = "PARTIAL_CANCELED".equals(status)
                ? "PARTIALLY_REFUNDED"
                : "REFUNDED";
        paymentRecordRepository.findByTransactionId(paymentKey).ifPresent(payment -> updatePaymentStatus(payment, mappedStatus));
        tossPaymentIntentService.markProviderTerminalStatus(paymentKey, orderId, status);
    }

    private void updatePaymentStatus(PaymentRecord payment, String status) {
        payment.setStatus(status);
        payment.setFailureReason(null);
        payment.setProcessedAt(LocalDateTime.now());
        paymentRecordRepository.save(payment);
    }

    private void markSucceeded(TossWebhookEvent event, String message) {
        event.setProcessingStatus("SUCCEEDED");
        event.setFailureReason(message);
        event.setProcessedAt(LocalDateTime.now());
        webhookEventRepository.save(event);
        increment("everysale.toss.webhook.processed", event.getEventType());
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
            if (value != null && !value.isNull() && !value.asText().isBlank()) {
                return value.asText();
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

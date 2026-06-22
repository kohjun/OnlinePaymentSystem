package com.example.payment.application.service;

import com.example.payment.domain.entity.PaymentIdempotencyRecord;
import com.example.payment.domain.repository.PaymentIdempotencyRepository;
import com.example.payment.infrastructure.tenancy.TenantContext;
import com.example.payment.presentation.dto.request.CompleteReservationRequest;
import com.example.payment.presentation.dto.response.CompleteReservationResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CompleteReservationIdempotencyService {

    private static final String OPERATION = "COMPLETE_RESERVATION";
    private static final int IDEMPOTENCY_TTL_HOURS = 24;

    private final PaymentIdempotencyRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional
    public IdempotencyDecision prepare(CompleteReservationRequest request, String workflowId) {
        String tenantId = defaultText(TenantContext.getTenantId(), "default");
        String merchantId = defaultText(request.getPaymentInfo().getMerchantId(), "default");
        String requestHash = requestHash(request);

        PaymentIdempotencyRecord existing = repository
                .findByTenantIdAndMerchantIdAndOperationAndIdempotencyKey(
                        tenantId,
                        merchantId,
                        OPERATION,
                        request.getIdempotencyKey()
                )
                .orElse(null);
        if (existing != null) {
            return decisionFromExisting(existing, requestHash);
        }

        PaymentIdempotencyRecord created = PaymentIdempotencyRecord.builder()
                .idempotencyId("IDEMP-" + UUID.randomUUID())
                .tenantId(tenantId)
                .merchantId(merchantId)
                .operation(OPERATION)
                .idempotencyKey(request.getIdempotencyKey())
                .requestHash(requestHash)
                .workflowId(workflowId)
                .status("IN_PROGRESS")
                .createdAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusHours(IDEMPOTENCY_TTL_HOURS))
                .build();
        try {
            repository.save(created);
            return IdempotencyDecision.start(workflowId);
        } catch (DataIntegrityViolationException race) {
            PaymentIdempotencyRecord raced = repository
                    .findByTenantIdAndMerchantIdAndOperationAndIdempotencyKey(
                            tenantId,
                            merchantId,
                            OPERATION,
                            request.getIdempotencyKey()
                    )
                    .orElseThrow(() -> race);
            return decisionFromExisting(raced, requestHash);
        }
    }

    @Transactional
    public void recordResponse(CompleteReservationRequest request, CompleteReservationResponse response) {
        String tenantId = defaultText(TenantContext.getTenantId(), "default");
        String merchantId = defaultText(request.getPaymentInfo().getMerchantId(), "default");
        repository.findByTenantIdAndMerchantIdAndOperationAndIdempotencyKey(
                        tenantId,
                        merchantId,
                        OPERATION,
                        request.getIdempotencyKey()
                )
                .ifPresent(record -> {
                    record.setWorkflowId(response.getWorkflowId());
                    record.setStatus(response.getStatus());
                    try {
                        record.setResponseBody(objectMapper.writeValueAsString(response));
                    } catch (JsonProcessingException e) {
                        record.setResponseBody(null);
                    }
                    repository.save(record);
                });
    }

    private IdempotencyDecision decisionFromExisting(PaymentIdempotencyRecord existing, String requestHash) {
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new IdempotencyConflictException("IDEMPOTENCY_KEY_CONFLICT");
        }
        CompleteReservationResponse stored = readStoredResponse(existing.getResponseBody());
        if (stored != null && !"PENDING".equals(stored.getStatus())) {
            return IdempotencyDecision.replay(stored);
        }
        return IdempotencyDecision.attach(existing.getWorkflowId());
    }

    private CompleteReservationResponse readStoredResponse(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(responseBody, CompleteReservationResponse.class);
        } catch (Exception e) {
            return null;
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
            throw new IllegalStateException("Failed to hash idempotency request", e);
        }
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    @Value
    @Builder
    public static class IdempotencyDecision {
        boolean startWorkflow;
        String workflowId;
        CompleteReservationResponse replayResponse;

        static IdempotencyDecision start(String workflowId) {
            return IdempotencyDecision.builder().startWorkflow(true).workflowId(workflowId).build();
        }

        static IdempotencyDecision attach(String workflowId) {
            return IdempotencyDecision.builder().startWorkflow(false).workflowId(workflowId).build();
        }

        static IdempotencyDecision replay(CompleteReservationResponse response) {
            return IdempotencyDecision.builder().startWorkflow(false).workflowId(response.getWorkflowId()).replayResponse(response).build();
        }
    }
}

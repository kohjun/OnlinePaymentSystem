package com.example.payment.application.service;

import com.example.payment.domain.entity.TossPaymentIntent;
import com.example.payment.domain.repository.*;
import com.example.payment.presentation.dto.request.TossPaymentConfirmRequest;
import com.example.payment.presentation.dto.response.CompleteReservationResponse;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Set;

@Service @RequiredArgsConstructor
public class TossIntentStateWriter {
    private final TossPaymentIntentRepository intents;
    private final InventoryReservationRecordRepository holds;
    private final ObjectMapper mapper;

    @Transactional
    public TossPaymentIntent authenticate(TossPaymentConfirmRequest request) {
        var hold = intents.findHoldId(request.getIntentId())
                .map(id -> holds.findByIdForUpdate(id).orElseThrow()).orElse(null);
        var intent = intents.findByIdForUpdate(request.getIntentId()).orElseThrow();
        if (!intent.getOrderId().equals(request.getOrderId()) || (intent.getPaymentKey() != null && !intent.getPaymentKey().equals(request.getPaymentKey()))) {
            throw new IllegalArgumentException("TOSS_PAYMENT_CONFLICT");
        }
        if (intent.getAmount().compareTo(request.getAmount()) != 0) throw new AmountMismatchException("AMOUNT_MISMATCH");
        if (intent.getPaymentKey() != null) return intent;
        if (!"READY".equals(intent.getStatus()) || !intent.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("TOSS_PAYMENT_INTENT_EXPIRED");
        }
        if (hold != null && (!"RESERVED".equals(hold.getStatus()) || !hold.getExpiresAt().isAfter(LocalDateTime.now()))) {
            throw new IllegalArgumentException("INVENTORY_HOLD_EXPIRED");
        }
        intent.setPaymentKey(request.getPaymentKey());
        intent.setStatus("AUTHENTICATED");
        return intents.saveAndFlush(intent);
    }

    @Transactional
    public TossPaymentIntent cancelReady(String intentId) {
        intents.findHoldId(intentId).ifPresent(id -> holds.findByIdForUpdate(id).orElseThrow());
        var intent = intents.findByIdForUpdate(intentId).orElseThrow();
        if (Set.of("CANCELLED", "CANCELED").contains(intent.getStatus())) return intent;
        if (!"READY".equals(intent.getStatus()) || intent.getPaymentKey() != null || intent.getWorkflowId() != null) {
            throw new IllegalArgumentException("TOSS_PAYMENT_INTENT_CANNOT_BE_CANCELLED");
        }
        intent.setStatus("CANCELLED");
        intent.setResponseBody(null);
        return intents.saveAndFlush(intent);
    }

    @Transactional
    public void recordResponse(String intentId, CompleteReservationResponse response) {
        var intent = intents.findByIdForUpdate(intentId).orElseThrow();
        if (Set.of("SUCCESS", "FAILED", "CANCELLED", "CANCELED").contains(intent.getStatus()) && intent.getResponseBody() != null) return;
        intent.setWorkflowId(response.getWorkflowId());
        intent.setStatus(response.isTerminal() ? response.getStatus() : "PENDING");
        try { intent.setResponseBody(response.isTerminal() ? mapper.writeValueAsString(response) : null); }
        catch (Exception failure) { throw new IllegalStateException("Cannot serialize checkout response", failure); }
        intents.save(intent);
    }
}

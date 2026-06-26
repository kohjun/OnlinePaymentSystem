package com.example.payment.presentation.controller;

import com.example.payment.application.service.AmountMismatchException;
import com.example.payment.application.service.IdempotencyConflictException;
import com.example.payment.application.service.TossPaymentIntentService;
import com.example.payment.application.service.TossWebhookService;
import com.example.payment.infrastructure.security.AuthorizationGuard;
import com.example.payment.presentation.dto.request.CompleteReservationRequest;
import com.example.payment.presentation.dto.request.TossPaymentConfirmRequest;
import com.example.payment.presentation.dto.response.CompleteReservationResponse;
import com.example.payment.presentation.dto.response.TossPaymentIntentResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments/toss")
@RequiredArgsConstructor
@Validated
@Slf4j
public class TossPaymentController {

    private final TossPaymentIntentService tossPaymentIntentService;
    private final TossWebhookService tossWebhookService;
    private final AuthorizationGuard authorizationGuard;

    @PostMapping("/webhooks/{token}")
    public ResponseEntity<?> receiveWebhook(@PathVariable String token, @RequestBody String rawPayload) {
        TossWebhookService.WebhookReceipt receipt = tossWebhookService.receive(token, rawPayload);
        return ResponseEntity.ok(java.util.Map.of(
                "status", "RECEIVED",
                "eventId", receipt.eventId(),
                "processingStatus", receipt.processingStatus()
        ));
    }

    @PostMapping("/intents")
    public ResponseEntity<?> createIntent(@Valid @RequestBody CompleteReservationRequest request) {
        try {
            authorizationGuard.requireCustomerAccess(request.getCustomerId());
            TossPaymentIntentResponse response = tossPaymentIntentService.createIntent(request);
            return ResponseEntity.ok(response);
        } catch (AmountMismatchException e) {
            return ResponseEntity.status(409).body(error("AMOUNT_MISMATCH", e.getMessage()));
        } catch (IdempotencyConflictException e) {
            return ResponseEntity.status(409).body(error("IDEMPOTENCY_KEY_CONFLICT", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error("INVALID_TOSS_PAYMENT_INTENT", e.getMessage()));
        }
    }

    @PostMapping("/confirm")
    public ResponseEntity<CompleteReservationResponse> confirm(@Valid @RequestBody TossPaymentConfirmRequest request) {
        try {
            authorizationGuard.requireTossIntentAccess(request.getIntentId());
            CompleteReservationResponse response = tossPaymentIntentService.confirm(request);
            if ("PENDING".equals(response.getStatus())) {
                return ResponseEntity.accepted().body(response);
            }
            if ("SUCCESS".equals(response.getStatus())) {
                return ResponseEntity.ok(response);
            }
            return ResponseEntity.badRequest().body(response);
        } catch (AmountMismatchException e) {
            return ResponseEntity.status(409).body(failed("AMOUNT_MISMATCH", e.getMessage()));
        } catch (IllegalArgumentException e) {
            String code = e.getMessage() != null && e.getMessage().startsWith("TOSS_PAYMENT_CONFLICT")
                    ? "TOSS_PAYMENT_CONFLICT"
                    : "INVALID_TOSS_CONFIRM_REQUEST";
            return ResponseEntity.status("TOSS_PAYMENT_CONFLICT".equals(code) ? 409 : 400)
                    .body(failed(code, e.getMessage()));
        }
    }

    private java.util.Map<String, Object> error(String code, String message) {
        return java.util.Map.of("status", "FAILED", "errorCode", code, "message", message);
    }

    private CompleteReservationResponse failed(String code, String message) {
        return CompleteReservationResponse.builder()
                .status("FAILED")
                .errorCode(code)
                .message(message)
                .timestamp(java.time.LocalDateTime.now())
                .build();
    }
}

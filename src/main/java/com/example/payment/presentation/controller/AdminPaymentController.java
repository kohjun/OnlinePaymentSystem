package com.example.payment.presentation.controller;

import com.example.payment.application.service.PaymentProcessingService;
import com.example.payment.infrastructure.security.AuthorizationGuard;
import com.example.payment.infrastructure.security.SecurityAuditService;
import com.example.payment.presentation.dto.request.AdminRefundRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/payments")
@RequiredArgsConstructor
@Slf4j
public class AdminPaymentController {

    private final PaymentProcessingService paymentProcessingService;
    private final AuthorizationGuard authorizationGuard;
    private final SecurityAuditService securityAuditService;

    @PostMapping("/{paymentId}/refund")
    public ResponseEntity<Map<String, Object>> refundPayment(@PathVariable String paymentId,
                                                             @Valid @RequestBody AdminRefundRequest request) {
        authorizationGuard.requireAdmin();
        securityAuditService.recordGranted("ADMIN_PAYMENT_REFUND_REQUESTED", "PAYMENT", paymentId);
        log.info("Admin refund requested: paymentId={}, idempotencyKey={}, reason={}",
                paymentId, request.getIdempotencyKey(), request.getReason());

        PaymentProcessingService.RefundResult result = paymentProcessingService.refundPaymentWithResult(
                paymentId,
                request.getIdempotencyKey(),
                request.getReason()
        );

        if (result.success()) {
            securityAuditService.recordGranted("ADMIN_PAYMENT_REFUND_SUCCEEDED", "PAYMENT", paymentId);
            return ResponseEntity.ok(body(result));
        }

        securityAuditService.record("ADMIN_PAYMENT_REFUND_FAILED", "PAYMENT", paymentId, "FAILED", result.message());
        return ResponseEntity.status(status(result.code())).body(body(result));
    }

    private HttpStatus status(String code) {
        if ("PAYMENT_NOT_FOUND".equals(code)) {
            return HttpStatus.NOT_FOUND;
        }
        if ("PAYMENT_NOT_REFUNDABLE".equals(code)) {
            return HttpStatus.CONFLICT;
        }
        return HttpStatus.BAD_GATEWAY;
    }

    private Map<String, Object> body(PaymentProcessingService.RefundResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", result.success() ? "SUCCESS" : "FAILED");
        body.put("code", result.code());
        body.put("message", result.message());
        if (result.payment() != null) {
            body.put("payment", result.payment());
        }
        return body;
    }
}
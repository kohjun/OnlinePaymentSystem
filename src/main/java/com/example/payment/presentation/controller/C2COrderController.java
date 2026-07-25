package com.example.payment.presentation.controller;

import com.example.payment.application.service.MarketplaceOrderService;
import com.example.payment.infrastructure.security.AuthorizationGuard;
import com.example.payment.infrastructure.security.EverySalePrincipal;
import com.example.payment.presentation.dto.request.OpenMarketplaceDisputeRequest;
import com.example.payment.presentation.dto.request.ResolveMarketplaceDisputeRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/c2c/orders")
@RequiredArgsConstructor
@Validated
@Slf4j
public class C2COrderController {

    private final AuthorizationGuard authorizationGuard;
    private final MarketplaceOrderService marketplaceOrderService;

    @PostMapping("/{marketplaceOrderId}/confirm-delivery")
    public ResponseEntity<?> confirmDelivery(@PathVariable String marketplaceOrderId) {
        try {
            EverySalePrincipal principal = authorizationGuard.currentPrincipal();
            return ResponseEntity.ok(marketplaceOrderService.confirmDelivery(
                    principal.customerId(),
                    marketplaceOrderId
            ));
        } catch (IllegalArgumentException e) {
            log.warn("C2C delivery confirmation rejected: {}", e.getMessage());
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/{marketplaceOrderId}/dispute")
    public ResponseEntity<?> openDispute(
            @PathVariable String marketplaceOrderId,
            @Valid @RequestBody OpenMarketplaceDisputeRequest request) {
        try {
            EverySalePrincipal principal = authorizationGuard.currentPrincipal();
            return ResponseEntity.ok(marketplaceOrderService.openDispute(
                    principal.customerId(),
                    marketplaceOrderId,
                    request.getReason()
            ));
        } catch (IllegalArgumentException e) {
            log.warn("C2C dispute request rejected: {}", e.getMessage());
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/{marketplaceOrderId}/dispute/resolve")
    public ResponseEntity<?> resolveDispute(
            @PathVariable String marketplaceOrderId,
            @Valid @RequestBody ResolveMarketplaceDisputeRequest request) {
        try {
            authorizationGuard.requireAdmin();
            EverySalePrincipal principal = authorizationGuard.currentPrincipal();
            return ResponseEntity.ok(marketplaceOrderService.resolveDispute(
                    principal.userId(),
                    marketplaceOrderId,
                    request.getResolution(),
                    request.getNote()
            ));
        } catch (IllegalArgumentException e) {
            log.warn("C2C dispute resolution rejected: {}", e.getMessage());
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "status", "FAILED",
                "message", message
        ));
    }
}

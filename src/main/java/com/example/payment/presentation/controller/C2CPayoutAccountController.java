package com.example.payment.presentation.controller;

import com.example.payment.application.service.SellerPayoutAccountService;
import com.example.payment.infrastructure.security.AuthorizationGuard;
import com.example.payment.infrastructure.security.EverySalePrincipal;
import com.example.payment.presentation.dto.request.SubmitSellerPayoutAccountRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/c2c/seller/payout-account")
@RequiredArgsConstructor
@Validated
@Slf4j
public class C2CPayoutAccountController {

    private final AuthorizationGuard authorizationGuard;
    private final SellerPayoutAccountService payoutAccountService;

    @GetMapping
    public ResponseEntity<?> getPayoutAccount() {
        try {
            EverySalePrincipal principal = authorizationGuard.currentPrincipal();
            return payoutAccountService.getForOwner(principal.userId(), principal.customerId())
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                            "status", "FAILED",
                            "message", "Seller payout account has not been submitted."
                    )));
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping
    public ResponseEntity<?> submitPayoutAccount(@Valid @RequestBody SubmitSellerPayoutAccountRequest request) {
        try {
            EverySalePrincipal principal = authorizationGuard.currentPrincipal();
            return ResponseEntity.ok(payoutAccountService.submitForOwner(
                    principal.userId(),
                    principal.customerId(),
                    request
            ));
        } catch (IllegalArgumentException e) {
            log.warn("C2C payout account submission rejected: {}", e.getMessage());
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

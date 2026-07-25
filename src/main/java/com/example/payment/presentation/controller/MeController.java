package com.example.payment.presentation.controller;

import com.example.payment.application.service.AccountService;
import com.example.payment.infrastructure.security.AuthorizationGuard;
import com.example.payment.infrastructure.security.EverySalePrincipal;
import com.example.payment.presentation.dto.request.CreateSellerRequest;
import com.example.payment.presentation.dto.response.SellerResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class MeController {

    private final AuthorizationGuard authorizationGuard;
    private final AccountService accountService;

    @GetMapping
    public ResponseEntity<?> getMe() {
        EverySalePrincipal principal = authorizationGuard.currentPrincipal();
        return ResponseEntity.ok(accountService.getMe(principal));
    }

    @GetMapping("/seller-profile")
    public ResponseEntity<?> getMySellerProfile() {
        EverySalePrincipal principal = authorizationGuard.currentPrincipal();
        return accountService.getMySellerProfile(principal)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                        "status", "FAILED",
                        "message", "Seller profile has not been created for the current user."
                )));
    }

    @PostMapping("/seller-profile")
    public ResponseEntity<SellerResponse> createMySellerProfile(@Valid @RequestBody CreateSellerRequest request) {
        EverySalePrincipal principal = authorizationGuard.currentPrincipal();
        SellerResponse response = accountService.createMySellerProfile(principal, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}

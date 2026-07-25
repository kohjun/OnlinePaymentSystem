package com.example.payment.presentation.controller;

import com.example.payment.application.service.ShippingAddressService;
import com.example.payment.infrastructure.security.AuthorizationGuard;
import com.example.payment.infrastructure.security.EverySalePrincipal;
import com.example.payment.presentation.dto.request.CreateShippingAddressRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/c2c/addresses")
@RequiredArgsConstructor
@Validated
@Slf4j
public class C2CAddressController {

    private final AuthorizationGuard authorizationGuard;
    private final ShippingAddressService shippingAddressService;

    @GetMapping
    public ResponseEntity<?> getMyAddresses() {
        EverySalePrincipal principal = authorizationGuard.currentPrincipal();
        return ResponseEntity.ok(shippingAddressService.getMyAddresses(principal));
    }

    @PostMapping
    public ResponseEntity<?> createAddress(@Valid @RequestBody CreateShippingAddressRequest request) {
        try {
            EverySalePrincipal principal = authorizationGuard.currentPrincipal();
            return ResponseEntity.status(HttpStatus.CREATED).body(shippingAddressService.createAddress(principal, request));
        } catch (IllegalArgumentException e) {
            log.warn("C2C shipping address creation rejected: {}", e.getMessage());
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PatchMapping("/{addressId}/default")
    public ResponseEntity<?> setDefaultAddress(@PathVariable String addressId) {
        try {
            EverySalePrincipal principal = authorizationGuard.currentPrincipal();
            return ResponseEntity.ok(shippingAddressService.setDefaultAddress(principal, addressId));
        } catch (IllegalArgumentException e) {
            log.warn("C2C default address update rejected: {}", e.getMessage());
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @DeleteMapping("/{addressId}")
    public ResponseEntity<?> deleteAddress(@PathVariable String addressId) {
        try {
            EverySalePrincipal principal = authorizationGuard.currentPrincipal();
            shippingAddressService.deleteAddress(principal, addressId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            log.warn("C2C shipping address delete rejected: {}", e.getMessage());
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

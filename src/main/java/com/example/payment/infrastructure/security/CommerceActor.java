package com.example.payment.infrastructure.security;

public record CommerceActor(String userId, String customerId) {
    public CommerceActor {
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("CUSTOMER_IDENTITY_REQUIRED");
        }
    }
}

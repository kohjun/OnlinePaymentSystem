package com.example.payment.infrastructure.security;

import java.util.Set;

public record EverySalePrincipal(
        String customerId,
        String sellerId,
        Set<String> roles
) {
}

package com.example.payment.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EverySaleJwtAuthenticationConverterTest {

    private final EverySaleJwtAuthenticationConverter converter = new EverySaleJwtAuthenticationConverter();

    @Test
    void convertsCommonRoleClaimsAndUsesCustomerIdAsPrincipalName() {
        Jwt jwt = jwt(Map.of(
                "sub", "subject-1",
                "customer_id", "CUS-1",
                "scope", "payments:read",
                "roles", List.of("admin", "seller"),
                "realm_access", Map.of("roles", List.of("support")),
                "resource_access", Map.of("everysale", Map.of("roles", List.of("operator")))
        ));

        AbstractAuthenticationToken authentication = converter.convert(jwt);
        Set<String> authorities = authorities(authentication);

        assertEquals("CUS-1", authentication.getName());
        assertTrue(authorities.contains("SCOPE_payments:read"));
        assertTrue(authorities.contains("ROLE_ADMIN"));
        assertTrue(authorities.contains("ROLE_SELLER"));
        assertTrue(authorities.contains("ROLE_SUPPORT"));
        assertTrue(authorities.contains("ROLE_OPERATOR"));
    }

    @Test
    void keepsExplicitRoleAndScopePrefixes() {
        Jwt jwt = jwt(Map.of(
                "sub", "subject-1",
                "authorities", "ROLE_ADMIN SCOPE_admin"
        ));

        AbstractAuthenticationToken authentication = converter.convert(jwt);
        Set<String> authorities = authorities(authentication);

        assertTrue(authorities.contains("ROLE_ADMIN"));
        assertTrue(authorities.contains("SCOPE_admin"));
    }

    private Jwt jwt(Map<String, Object> claims) {
        return new Jwt(
                "token",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "none"),
                claims
        );
    }

    private Set<String> authorities(AbstractAuthenticationToken authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }
}
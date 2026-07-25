package com.example.payment.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtAudienceValidatorTest {

    private final JwtAudienceValidator validator = new JwtAudienceValidator(Set.of("everysale-api"));

    @Test
    void acceptsConfiguredAudience() {
        assertFalse(validator.validate(jwt(List.of("everysale-api"))).hasErrors());
    }

    @Test
    void rejectsTokenIssuedForAnotherApplication() {
        assertTrue(validator.validate(jwt(List.of("another-api"))).hasErrors());
    }

    private Jwt jwt(List<String> audiences) {
        return new Jwt(
                "token",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "RS256"),
                Map.of("sub", "subject-1", "aud", audiences)
        );
    }
}

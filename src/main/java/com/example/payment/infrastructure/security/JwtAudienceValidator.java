package com.example.payment.infrastructure.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Set;

public class JwtAudienceValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error INVALID_AUDIENCE = new OAuth2Error(
            "invalid_token",
            "The required EverySale audience is missing.",
            null
    );

    private final Set<String> requiredAudiences;

    public JwtAudienceValidator(Set<String> requiredAudiences) {
        if (requiredAudiences == null || requiredAudiences.isEmpty()) {
            throw new IllegalArgumentException("At least one JWT audience is required.");
        }
        this.requiredAudiences = Set.copyOf(requiredAudiences);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        return token.getAudience().stream().anyMatch(requiredAudiences::contains)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(INVALID_AUDIENCE);
    }
}

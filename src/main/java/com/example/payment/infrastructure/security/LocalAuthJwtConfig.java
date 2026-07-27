package com.example.payment.infrastructure.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * 자체 발급 토큰의 서명과 검증.
 *
 * 발급기와 검증기가 같은 비밀키를 공유한다. 발급과 검증이 한 프로세스 안에
 * 있으므로 대칭키로 충분하다.
 */
@Configuration
@ConditionalOnProperty(name = "app.security.local-auth.enabled", havingValue = "true", matchIfMissing = true)
public class LocalAuthJwtConfig {

    /** HMAC-SHA256 서명에 필요한 최소 키 길이. */
    private static final int MINIMUM_SECRET_BYTES = 32;

    private SecretKeySpec secretKey(LocalAuthProperties properties) {
        String secret = properties.getJwtSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MINIMUM_SECRET_BYTES) {
            throw new IllegalStateException(
                    "app.security.local-auth.jwt-secret must be at least " + MINIMUM_SECRET_BYTES
                            + " bytes. Set LOCAL_AUTH_JWT_SECRET to a random value.");
        }
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    public JwtEncoder localAuthJwtEncoder(LocalAuthProperties properties) {
        JWKSource<SecurityContext> jwkSource = new ImmutableSecret<>(secretKey(properties));
        return new NimbusJwtEncoder(jwkSource);
    }

    /**
     * 자체 발급 토큰 검증기.
     *
     * 외부 인증이 켜져 있으면 만들지 않는다. SecurityConfig가 OIDC 발급자용
     * 디코더를 이미 등록하고 있어, 둘이 공존하면 어느 발급자를 신뢰하는지
     * 모호해지고 빈 충돌도 난다.
     */
    @Bean
    @ConditionalOnProperty(name = "app.security.external-auth.enabled", havingValue = "false", matchIfMissing = true)
    public JwtDecoder jwtDecoder(LocalAuthProperties properties) {
        return NimbusJwtDecoder.withSecretKey(secretKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}

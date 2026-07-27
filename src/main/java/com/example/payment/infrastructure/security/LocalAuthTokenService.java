package com.example.payment.infrastructure.security;

import com.example.payment.domain.model.account.UserAccount;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 자체 계정 로그인 시 액세스 토큰을 발급한다.
 *
 * 클레임 이름은 EverySaleJwtAuthenticationConverter와 AuthorizationGuard가
 * 읽는 것과 정확히 맞춘다. 그래야 자체 발급 토큰이 외부 IdP 토큰과 같은
 * 인가 경로를 그대로 탄다. 이름이 어긋나면 인증은 통과하는데 customerId가
 * 비어 소유권 검사가 조용히 어긋난다.
 */
@Service
public class LocalAuthTokenService {

    private final JwtEncoder jwtEncoder;
    private final LocalAuthProperties properties;

    public LocalAuthTokenService(JwtEncoder localAuthJwtEncoder, LocalAuthProperties properties) {
        this.jwtEncoder = localAuthJwtEncoder;
        this.properties = properties;
    }

    public IssuedToken issue(UserAccount account, List<String> roles) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.getTokenValidityMinutes(), ChronoUnit.MINUTES);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.getIssuer())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .subject(account.getUserId())
                .claim("userId", account.getUserId())
                .claim("customerId", account.getCustomerId())
                .claim("email", account.getEmail())
                .claim("roles", roles)
                .build();

        String token = jwtEncoder
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();

        return new IssuedToken(token, expiresAt, properties.getTokenValidityMinutes() * 60);
    }

    public record IssuedToken(String accessToken, Instant expiresAt, long expiresInSeconds) {
    }
}

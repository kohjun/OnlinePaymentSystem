package com.example.payment.infrastructure.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 자체 계정 로그인 설정.
 *
 * 외부 IdP를 붙이기 전 단계에서 회원가입/로그인을 제공한다. 서버가 직접
 * 서명한 JWT를 발급하고, 리소스 서버는 같은 비밀키로 그 토큰을 검증한다.
 * 발급과 검증이 한 프로세스 안에 있으므로 대칭키로 충분하다.
 *
 * 외부 인증(app.security.external-auth.enabled)을 켜면 이 경로는 꺼야 한다.
 * 두 발급자를 동시에 신뢰하면 어느 쪽이 토큰을 만들었는지 알 수 없다.
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.security.local-auth")
public class LocalAuthProperties {

    private boolean enabled = true;

    /**
     * HMAC 서명 비밀키. 최소 32바이트여야 한다.
     * 운영에서는 반드시 환경변수로 주입하고, 기본값에 의존하면 안 된다.
     */
    private String jwtSecret = "";

    private String issuer = "everysale-local";

    /** 액세스 토큰 유효 시간(분). */
    private long tokenValidityMinutes = 720;
}

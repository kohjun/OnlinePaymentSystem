package com.example.payment.application.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 결제 후 Toss가 브라우저를 돌려보낼 주소를 제한한다.
 *
 * successUrl·failUrl은 요청 본문에서 오고 intent에 저장된 뒤 결제창에 그대로 넘어간다.
 * 검증이 없으면 결제를 마친 사용자를 임의의 외부 사이트로 보내는 열린 리다이렉트가
 * 된다. 같은 사이트 상대 경로는 항상 허용하고, 절대 주소는 API를 부를 수 있도록
 * 허용된 출처(app.security.cors.allowed-origins)일 때만 받는다.
 */
@Component
public class CheckoutRedirectPolicy {

    private final Set<String> allowedOrigins;

    public CheckoutRedirectPolicy(@Value("${app.security.cors.allowed-origins:}") String allowedOrigins) {
        this.allowedOrigins = Arrays.stream(allowedOrigins == null ? new String[0] : allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty() && !origin.contains("*"))
                .map(CheckoutRedirectPolicy::originOf)
                .filter(origin -> origin != null)
                .collect(Collectors.toUnmodifiableSet());
    }

    public String requireAllowed(String url) {
        if (url == null || url.isBlank() || url.chars().anyMatch(ch -> ch <= 0x20 || ch == '\\' || ch == 0x7f)) {
            throw rejected();
        }
        if (url.startsWith("/")) {
            // "//host"는 브라우저가 다른 호스트로 해석하는 스킴 상대 주소다.
            if (url.startsWith("//")) throw rejected();
            return url;
        }
        String origin = originOf(url);
        if (origin == null || !allowedOrigins.contains(origin)) {
            throw rejected();
        }
        return url;
    }

    private static String originOf(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) return null;
        if (uri.getHost() == null || uri.getRawUserInfo() != null) return null;
        int port = uri.getPort();
        boolean defaultPort = port == -1 || ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port);
    }

    private static MarketplaceCheckoutException rejected() {
        return new MarketplaceCheckoutException(
                HttpStatus.BAD_REQUEST,
                "INVALID_REDIRECT_URL",
                "결제 후 이동할 주소는 이 서비스의 경로이거나 허용된 출처여야 합니다."
        );
    }
}

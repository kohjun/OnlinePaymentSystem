package com.example.payment.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class MockAuthenticationFilter extends OncePerRequestFilter {

    @Value("${app.security.mock-auth.enabled:false}")
    private boolean enabled;

    @Value("${app.security.mock-auth.default-customer-id:demo-customer}")
    private String defaultCustomerId;

    @Value("${app.security.mock-auth.default-user-id:}")
    private String defaultUserId;

    @Value("${app.security.mock-auth.default-roles:CUSTOMER}")
    private String defaultRoles;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // Bearer 토큰이 실려 있으면 목 인증은 비켜선다. 토큰 검증 필터보다
        // 먼저 도는 위치라, 여기서 신원을 채워버리면 로그인해서 받은 토큰이
        // 무시되고 모든 요청이 기본 계정으로 처리된다.
        if (enabled
                && SecurityContextHolder.getContext().getAuthentication() == null
                && !hasBearerToken(request)) {
            String customerId = firstHeader(request, "X-EverySale-Customer-Id", "X-Customer-Id");
            String resolvedCustomerId = defaultText(customerId, defaultCustomerId);
            String userId = firstHeader(request, "X-EverySale-User-Id", "X-User-Id");
            String sellerId = firstHeader(request, "X-EverySale-Seller-Id", "X-Seller-Id");
            String rolesHeader = firstHeader(request, "X-EverySale-Roles", "X-Roles");
            Set<String> roles = roles(defaultText(rolesHeader, defaultRoles));
            EverySalePrincipal principal = new EverySalePrincipal(
                    defaultText(userId, defaultText(defaultUserId, "USER-" + resolvedCustomerId)),
                    resolvedCustomerId,
                    sellerId,
                    roles
            );
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    principal,
                    "mock-auth",
                    roles.stream()
                            .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                            .toList()
            );
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        filterChain.doFilter(request, response);
    }

    private boolean hasBearerToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        return authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7);
    }

    private String firstHeader(HttpServletRequest request, String... names) {
        for (String name : names) {
            String value = request.getHeader(name);
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    private Set<String> roles(String value) {
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(role -> !role.isEmpty())
                .map(role -> role.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}

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

    @Value("${app.security.mock-auth.default-roles:CUSTOMER}")
    private String defaultRoles;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (enabled && SecurityContextHolder.getContext().getAuthentication() == null) {
            String customerId = firstHeader(request, "X-EverySale-Customer-Id", "X-Customer-Id", "X-User-Id");
            String sellerId = firstHeader(request, "X-EverySale-Seller-Id", "X-Seller-Id");
            String rolesHeader = firstHeader(request, "X-EverySale-Roles", "X-Roles");
            Set<String> roles = roles(defaultText(rolesHeader, defaultRoles));
            EverySalePrincipal principal = new EverySalePrincipal(
                    defaultText(customerId, defaultCustomerId),
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

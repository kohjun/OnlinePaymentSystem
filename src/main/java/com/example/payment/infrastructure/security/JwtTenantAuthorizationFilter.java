package com.example.payment.infrastructure.security;

import com.example.payment.infrastructure.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class JwtTenantAuthorizationFilter extends OncePerRequestFilter {

    private final boolean enabled;
    private final boolean requireTenantClaim;
    private final boolean requirePartnerClaim;

    public JwtTenantAuthorizationFilter(boolean enabled,
                                        boolean requireTenantClaim,
                                        boolean requirePartnerClaim) {
        this.enabled = enabled;
        this.requireTenantClaim = requireTenantClaim;
        this.requirePartnerClaim = requirePartnerClaim;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!enabled || isExcluded(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            filterChain.doFilter(request, response);
            return;
        }

        String tokenTenantId = firstText(jwt, "tenantId", "tenant_id", "tid");
        String tokenPartnerId = firstText(jwt, "partnerId", "partner_id", "pid", "merchant_id");
        if (requireTenantClaim && tokenTenantId == null) {
            writeForbidden(response, "Authenticated token is missing the tenant claim.");
            return;
        }
        if (requirePartnerClaim && tokenPartnerId == null) {
            writeForbidden(response, "Authenticated token is missing the partner claim.");
            return;
        }
        if (tokenTenantId != null && !tokenTenantId.equals(TenantContext.getTenantId())) {
            writeForbidden(response, "Tenant ownership mismatch.");
            return;
        }
        if (tokenPartnerId != null && !tokenPartnerId.equals(TenantContext.getPartnerId())) {
            writeForbidden(response, "Partner ownership mismatch.");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isExcluded(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null
                || !path.startsWith("/api/")
                || path.startsWith("/api/system/health")
                || path.startsWith("/api/system/readiness")
                || path.startsWith("/api/payments/toss/webhooks/");
    }

    private String firstText(Jwt jwt, String... names) {
        for (String name : names) {
            Object value = jwt.getClaims().get(name);
            if (value != null && !value.toString().isBlank()) {
                return value.toString().trim();
            }
        }
        return null;
    }

    private void writeForbidden(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"status\":\"FAILED\",\"message\":\"" + message + "\"}");
    }
}

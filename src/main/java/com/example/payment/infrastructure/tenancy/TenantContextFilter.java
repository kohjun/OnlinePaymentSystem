package com.example.payment.infrastructure.tenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantContextFilter extends OncePerRequestFilter {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}");

    @Value("${app.tenancy.default-tenant-id:everysale-demo}")
    private String defaultTenantId;

    @Value("${app.tenancy.default-partner-id:demo-partner}")
    private String defaultPartnerId;

    @Value("${app.tenancy.require-tenant-header:false}")
    private boolean requireTenantHeader;

    @Value("${app.tenancy.response-headers-enabled:true}")
    private boolean responseHeadersEnabled;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String tenantId = trimToNull(request.getHeader("X-Tenant-Id"));
        String partnerId = trimToNull(request.getHeader("X-Partner-Id"));
        String correlationId = trimToNull(request.getHeader("X-Correlation-Id"));

        if (requiresTenantHeader(request) && tenantId == null) {
            writeBadRequest(response, "X-Tenant-Id header is required for B2B SaaS API requests.");
            return;
        }

        tenantId = tenantId != null ? tenantId : defaultTenantId;
        partnerId = partnerId != null ? partnerId : defaultPartnerId;
        correlationId = correlationId != null ? correlationId : UUID.randomUUID().toString();

        if (!isSafeIdentifier(tenantId) || !isSafeIdentifier(partnerId)) {
            writeBadRequest(response, "Tenant and partner identifiers may contain only letters, numbers, '.', '_', ':' and '-'.");
            return;
        }

        TenantContext.set(tenantId, partnerId, correlationId);
        MDC.put("tenantId", tenantId);
        MDC.put("partnerId", partnerId);
        MDC.put("correlationId", correlationId);

        if (responseHeadersEnabled) {
            response.setHeader("X-Tenant-Id", tenantId);
            response.setHeader("X-Partner-Id", partnerId);
            response.setHeader("X-Correlation-Id", correlationId);
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            MDC.remove("tenantId");
            MDC.remove("partnerId");
            MDC.remove("correlationId");
        }
    }

    private boolean requiresTenantHeader(HttpServletRequest request) {
        if (!requireTenantHeader) {
            return false;
        }
        String path = request.getRequestURI();
        return path != null
                && path.startsWith("/api/")
                && !path.startsWith("/api/system/health")
                && !path.startsWith("/api/system/readiness")
                && !path.startsWith("/api/payments/toss/webhooks/");
    }

    private boolean isSafeIdentifier(String value) {
        return value != null && SAFE_IDENTIFIER.matcher(value).matches();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void writeBadRequest(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"status\":\"FAILED\",\"message\":\"" + message + "\"}");
    }
}

package com.example.payment.infrastructure.security;

import com.example.payment.infrastructure.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtTenantAuthorizationFilterTest {

    private final JwtTenantAuthorizationFilter filter = new JwtTenantAuthorizationFilter(true, true, true);

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void allowsMatchingTenantAndPartnerClaims() throws Exception {
        authenticate("tenant-a", "partner-a");
        TenantContext.set("tenant-a", "partner-a", "corr-1");
        AtomicBoolean invoked = new AtomicBoolean();
        MockHttpServletResponse response = execute(invoked, "/api/orders/ORD-1");

        assertTrue(invoked.get());
        assertEquals(200, response.getStatus());
    }

    @Test
    void rejectsSpoofedTenantHeaderContext() throws Exception {
        authenticate("tenant-a", "partner-a");
        TenantContext.set("tenant-b", "partner-a", "corr-1");
        AtomicBoolean invoked = new AtomicBoolean();
        MockHttpServletResponse response = execute(invoked, "/api/orders/ORD-1");

        assertFalse(invoked.get());
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("Tenant ownership mismatch"));
    }

    @Test
    void rejectsTokenWithoutRequiredPartnerClaim() throws Exception {
        Jwt jwt = jwt(Map.of("sub", "user-1", "tenant_id", "tenant-a"));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        TenantContext.set("tenant-a", "partner-a", "corr-1");
        AtomicBoolean invoked = new AtomicBoolean();
        MockHttpServletResponse response = execute(invoked, "/api/orders/ORD-1");

        assertFalse(invoked.get());
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("missing the partner claim"));
    }

    private MockHttpServletResponse execute(AtomicBoolean invoked, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> invoked.set(true));
        return response;
    }

    private void authenticate(String tenantId, String partnerId) {
        Jwt jwt = jwt(Map.of(
                "sub", "user-1",
                "tenant_id", tenantId,
                "partner_id", partnerId
        ));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private Jwt jwt(Map<String, Object> claims) {
        return new Jwt(
                "token",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "RS256"),
                claims
        );
    }
}

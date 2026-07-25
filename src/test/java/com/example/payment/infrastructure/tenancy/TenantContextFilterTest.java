package com.example.payment.infrastructure.tenancy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantContextFilterTest {

    @Test
    void singleTenantModePinsAnonymousRequestsToConfiguredTenant() throws Exception {
        TenantContextFilter filter = filter("SINGLE_TENANT", "everysale", false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/marketplace/events");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> observedTenant = new AtomicReference<>();

        filter.doFilterInternal(request, response, (servletRequest, servletResponse) ->
                observedTenant.set(TenantContext.getTenantId()));

        assertEquals(200, response.getStatus());
        assertEquals("everysale", observedTenant.get());
        assertEquals("everysale", response.getHeader("X-Tenant-Id"));
        assertNull(TenantContext.getTenantId());
    }

    @Test
    void singleTenantModeRejectsTenantSpoofing() throws Exception {
        TenantContextFilter filter = filter("SINGLE_TENANT", "everysale", false);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/payments/toss/intents");
        request.addHeader("X-Tenant-Id", "other-marketplace");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilterInternal(request, response, (servletRequest, servletResponse) -> invoked.set(true));

        assertEquals(403, response.getStatus());
        assertFalse(invoked.get());
        assertTrue(response.getContentAsString().contains("not available"));
    }

    @Test
    void multiTenantModeStillRequiresExplicitTenantHeader() throws Exception {
        TenantContextFilter filter = filter("MULTI_TENANT_RLS", "", true);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/payments/toss/intents");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilterInternal(request, response, (servletRequest, servletResponse) -> invoked.set(true));

        assertEquals(400, response.getStatus());
        assertFalse(invoked.get());
        assertTrue(response.getContentAsString().contains("X-Tenant-Id"));
    }

    @Test
    void singleTenantModeFailsClosedWhenAllowedTenantIsMissing() throws Exception {
        TenantContextFilter filter = filter("SINGLE_TENANT", "", false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/marketplace/events");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, (servletRequest, servletResponse) -> {
            throw new AssertionError("Filter chain must not run with an invalid tenant configuration.");
        });

        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString().contains("allowed-tenant-id"));
    }

    private TenantContextFilter filter(String mode, String allowedTenantId, boolean requireTenantHeader) {
        TenantContextFilter filter = new TenantContextFilter();
        ReflectionTestUtils.setField(filter, "tenancyMode", mode);
        ReflectionTestUtils.setField(filter, "allowedTenantId", allowedTenantId);
        ReflectionTestUtils.setField(filter, "defaultTenantId", "everysale-demo");
        ReflectionTestUtils.setField(filter, "defaultPartnerId", "marketplace");
        ReflectionTestUtils.setField(filter, "requireTenantHeader", requireTenantHeader);
        ReflectionTestUtils.setField(filter, "responseHeadersEnabled", true);
        return filter;
    }
}

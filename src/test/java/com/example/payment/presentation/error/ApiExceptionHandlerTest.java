package com.example.payment.presentation.error;

import com.example.payment.application.service.IdempotencyConflictException;
import com.example.payment.infrastructure.tenancy.TenantContext;
import com.example.payment.infrastructure.util.ResourceReservationInfrastructureException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        TenantContext.set("tenant-1", "partner-1", "corr-test-1");
        mockMvc = MockMvcBuilders.standaloneSetup(new FailureController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void idempotencyConflictUsesStableProblemContract() throws Exception {
        mockMvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"))
                .andExpect(jsonPath("$.correlationId").value("corr-test-1"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void inventoryInfrastructureFailureIsRetryable() throws Exception {
        mockMvc.perform(get("/test/inventory"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RESERVATION_INFRASTRUCTURE_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void missingStaticResourceIsNotReportedAsInternalError() throws Exception {
        mockMvc.perform(get("/test/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void unsupportedMethodIsNotReportedAsInternalError() throws Exception {
        mockMvc.perform(post("/test/conflict"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @RestController
    static class FailureController {
        @GetMapping("/test/conflict")
        void conflict() {
            throw new IdempotencyConflictException("conflict");
        }

        @GetMapping("/test/inventory")
        void inventory() {
            throw new ResourceReservationInfrastructureException("redis unavailable");
        }

        @GetMapping("/test/missing")
        void missing() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "/missing");
        }
    }
}

package com.example.payment.application.service;

import com.example.payment.domain.entity.SecurityAuditEvent;
import com.example.payment.domain.repository.SecurityAuditEventRepository;
import com.example.payment.presentation.dto.response.AdminOperationAuditResponse;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminOperationsAuditServiceTest {

    private final SecurityAuditEventRepository repository = mock(SecurityAuditEventRepository.class);
    private final AdminOperationsAuditService service = new AdminOperationsAuditService(repository);

    @Test
    void returnsRecentOperationAuditEvents() {
        when(repository.findByActionStartingWithOrderByCreatedAtDesc(eq("ADMIN_OPERATION_"), any(Pageable.class)))
                .thenReturn(List.of(event("AUD-1", "listingReviews", "LIST-1", "GRANTED")));

        AdminOperationAuditResponse response = service.getAuditEvents(null, null, 5);

        assertNotNull(response.getGeneratedAt());
        assertEquals(1, response.getEvents().size());
        assertEquals("AUD-1", response.getEvents().get(0).getEventId());
        assertEquals("listingReviews", response.getEvents().get(0).getQueue());
        verify(repository).findByActionStartingWithOrderByCreatedAtDesc(eq("ADMIN_OPERATION_"), any(Pageable.class));
    }

    @Test
    void filtersAuditEventsByQueue() {
        when(repository.findByActionStartingWithAndResourceTypeOrderByCreatedAtDesc(
                eq("ADMIN_OPERATION_"), eq("payoutRelease"), any(Pageable.class)))
                .thenReturn(List.of(event("AUD-2", "payoutRelease", "PAYOUT-1", "GRANTED")));

        AdminOperationAuditResponse response = service.getAuditEvents("payoutRelease", null, 10);

        assertEquals("PAYOUT-1", response.getEvents().get(0).getResourceId());
        verify(repository).findByActionStartingWithAndResourceTypeOrderByCreatedAtDesc(
                eq("ADMIN_OPERATION_"), eq("payoutRelease"), any(Pageable.class));
    }

    @Test
    void filtersAuditEventsByQueueAndItem() {
        when(repository.findByActionStartingWithAndResourceTypeAndResourceIdOrderByCreatedAtDesc(
                eq("ADMIN_OPERATION_"), eq("payoutRecovery"), eq("PAYOUT-2"), any(Pageable.class)))
                .thenReturn(List.of(event("AUD-3", "payoutRecovery", "PAYOUT-2", "FAILED")));

        AdminOperationAuditResponse response = service.getAuditEvents(" payoutRecovery ", " PAYOUT-2 ", 200);

        assertEquals("FAILED", response.getEvents().get(0).getOutcome());
        verify(repository).findByActionStartingWithAndResourceTypeAndResourceIdOrderByCreatedAtDesc(
                eq("ADMIN_OPERATION_"), eq("payoutRecovery"), eq("PAYOUT-2"), any(Pageable.class));
    }

    private SecurityAuditEvent event(String eventId, String queue, String resourceId, String outcome) {
        return SecurityAuditEvent.builder()
                .eventId(eventId)
                .actorId("ops-admin")
                .actorRoles("ROLE_ADMIN")
                .action("ADMIN_OPERATION_RELEASEPAYOUT")
                .resourceType(queue)
                .resourceId(resourceId)
                .outcome(outcome)
                .reason(outcome.equals("FAILED") ? "Rejected by provider." : null)
                .ipAddress("127.0.0.1")
                .userAgent("JUnit")
                .createdAt(LocalDateTime.of(2026, 7, 9, 12, 0))
                .build();
    }
}

package com.example.payment.application.service;

import com.example.payment.domain.entity.SecurityAuditEvent;
import com.example.payment.domain.repository.SecurityAuditEventRepository;
import com.example.payment.presentation.dto.response.AdminOperationAuditResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminOperationsAuditService {

    private static final String ADMIN_OPERATION_PREFIX = "ADMIN_OPERATION_";

    private final SecurityAuditEventRepository auditEventRepository;

    @Transactional(readOnly = true)
    public AdminOperationAuditResponse getAuditEvents(String queue, String itemId, Integer limit) {
        Pageable pageable = PageRequest.of(0, normalizeLimit(limit));
        List<SecurityAuditEvent> events;
        String normalizedQueue = trimToNull(queue);
        String normalizedItemId = trimToNull(itemId);

        if (normalizedQueue != null && normalizedItemId != null) {
            events = auditEventRepository.findByActionStartingWithAndResourceTypeAndResourceIdOrderByCreatedAtDesc(
                    ADMIN_OPERATION_PREFIX,
                    normalizedQueue,
                    normalizedItemId,
                    pageable
            );
        } else if (normalizedQueue != null) {
            events = auditEventRepository.findByActionStartingWithAndResourceTypeOrderByCreatedAtDesc(
                    ADMIN_OPERATION_PREFIX,
                    normalizedQueue,
                    pageable
            );
        } else {
            events = auditEventRepository.findByActionStartingWithOrderByCreatedAtDesc(
                    ADMIN_OPERATION_PREFIX,
                    pageable
            );
        }

        return AdminOperationAuditResponse.builder()
                .generatedAt(LocalDateTime.now())
                .events(events.stream().map(this::toResponse).toList())
                .build();
    }

    private AdminOperationAuditResponse.AuditEvent toResponse(SecurityAuditEvent event) {
        return AdminOperationAuditResponse.AuditEvent.builder()
                .eventId(event.getEventId())
                .actorId(event.getActorId())
                .actorRoles(event.getActorRoles())
                .action(event.getAction())
                .queue(event.getResourceType())
                .resourceId(event.getResourceId())
                .outcome(event.getOutcome())
                .reason(event.getReason())
                .ipAddress(event.getIpAddress())
                .userAgent(event.getUserAgent())
                .createdAt(event.getCreatedAt())
                .build();
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return 10;
        }
        return Math.max(1, Math.min(limit, 100));
    }

    private String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}

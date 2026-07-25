package com.example.payment.application.service;

import com.example.payment.domain.entity.InventorySyncIssue;
import com.example.payment.domain.repository.InventorySyncIssueRepository;
import com.example.payment.infrastructure.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventorySyncIssueService {

    private final InventorySyncIssueRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public InventorySyncIssue record(String reservationId,
                                     String productId,
                                     int quantity,
                                     String issueType,
                                     Throwable failure) {
        return repository.save(InventorySyncIssue.builder()
                .issueId("INV-SYNC-" + IdGenerator.generateEventId())
                .reservationId(reservationId)
                .productId(productId)
                .quantity(quantity)
                .issueType(issueType)
                .status("PENDING")
                .attemptCount(0)
                .failureReason(safeMessage(failure))
                .build());
    }

    private String safeMessage(Throwable failure) {
        if (failure == null) {
            return "Unknown inventory synchronization failure";
        }
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        return message.length() <= 2000 ? message : message.substring(0, 2000);
    }
}

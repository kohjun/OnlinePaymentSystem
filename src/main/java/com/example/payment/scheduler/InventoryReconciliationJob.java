package com.example.payment.scheduler;

import com.example.payment.domain.model.inventory.Inventory;
import com.example.payment.domain.repository.InventoryRepository;
import com.example.payment.domain.repository.InventoryReservationRecordRepository;
import com.example.payment.domain.repository.InventorySyncIssueRepository;
import com.example.payment.domain.entity.InventoryReservationRecord;
import com.example.payment.domain.entity.InventorySyncIssue;
import com.example.payment.application.service.InventorySyncIssueService;
import com.example.payment.infrastructure.util.ResourceReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.inventory.reconciliation.enabled", havingValue = "true")
public class InventoryReconciliationJob {

    private final InventoryRepository inventoryRepository;
    private final ResourceReservationService resourceReservationService;
    private final InventoryReservationRecordRepository inventoryReservationRecordRepository;
    private final PlatformTransactionManager transactionManager;
    private final RedisTemplate<String, Object> redisTemplate;
    private final InventorySyncIssueRepository inventorySyncIssueRepository;
    private final InventorySyncIssueService inventorySyncIssueService;
    private final com.example.payment.application.service.InventoryHoldService holds;
    private final com.example.payment.application.service.InventoryReleasePolicy releasePolicy;

    @Scheduled(fixedDelayString = "${app.inventory.reconciliation.fixed-delay-ms:60000}")
    public void reconcileInventoryCounters() {
        reconcileSyncIssues();
        cleanupExpiredReservations();
        for (InventoryMismatch mismatch : findMismatches()) {
            // Counter snapshots race live reservations. Never overwrite live Redis counters from a scan.
            log.warn("Inventory reconciliation requires review: {}", mismatch);
        }
    }

    private void cleanupExpiredReservations() {
        for (InventoryReservationRecord reservation : inventoryReservationRecordRepository
                .findByStatusAndExpiresAtBefore("RESERVED", LocalDateTime.now())) {
            try {
                if (holds.release(reservation.getReservationId(), "CANCELLED") && reservation.getSeatId() != null) {
                    releaseSeatLock(reservation);
                }
            } catch (RuntimeException failure) {
                log.warn("Reservation expiry deferred: reservationId={}", reservation.getReservationId(), failure);
            }
        }
    }

    private void reconcileSyncIssues() {
        List<InventorySyncIssue> issues = inventorySyncIssueRepository
                .findByStatusOrderByCreatedAtAsc("PENDING", PageRequest.of(0, 50));
        for (InventorySyncIssue issue : issues) {
            try {
                if ("RESERVE_RELEASE_REQUIRED".equals(issue.getIssueType())
                        || "ROLLBACK_RELEASE_REQUIRED".equals(issue.getIssueType())
                        || "RELEASE_REDIS_REQUIRED".equals(issue.getIssueType())) {
                    resourceReservationService.releaseResource(
                            "inventory:" + issue.getProductId(),
                            issue.getQuantity(),
                            issue.getReservationId()
                    );
                    resolveIssue(issue.getIssueId());
                } else {
                    if ("RESTORE_REDIS_REQUIRED".equals(issue.getIssueType())) {
                        resourceReservationService.restoreConfirmedResource("inventory:" + issue.getProductId(),
                                issue.getQuantity(), issue.getReservationId());
                        resolveIssue(issue.getIssueId());
                    } else {
                        applyDatabaseRepair(issue);
                    }
                }
            } catch (RuntimeException failure) {
                markIssueAttemptFailed(issue.getIssueId(), failure);
                log.warn("Inventory synchronization issue retry failed: issueId={}, type={}",
                        issue.getIssueId(), issue.getIssueType(), failure);
            }
        }
    }

    private void applyDatabaseRepair(InventorySyncIssue issue) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            InventorySyncIssue currentIssue = inventorySyncIssueRepository.findById(issue.getIssueId())
                    .orElseThrow(() -> new IllegalStateException("Inventory sync issue not found."));
            if (!"PENDING".equals(currentIssue.getStatus())) {
                return;
            }
            InventoryReservationRecord reservation = inventoryReservationRecordRepository
                    .findByIdForUpdate(currentIssue.getReservationId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Inventory reservation not found: " + currentIssue.getReservationId()));
            if ("RELEASE_DB_APPLY_REQUIRED".equals(currentIssue.getIssueType())) {
                // Legacy repair records must use the same fence and idempotent release as expiry/Saga.
                holds.compensate(currentIssue.getReservationId());
                currentIssue.setStatus("RESOLVED");
                currentIssue.setResolvedAt(LocalDateTime.now());
                currentIssue.setFailureReason(null);
                inventorySyncIssueRepository.save(currentIssue);
                return;
            }
            Inventory inventory = inventoryRepository.findByIdForUpdate(currentIssue.getProductId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Inventory not found: " + currentIssue.getProductId()));

            if ("CONFIRM_DB_APPLY_REQUIRED".equals(currentIssue.getIssueType())) {
                if ("RESERVED".equals(reservation.getStatus())) {
                    reservation.setStatus("CONFIRMED");
                    inventory.setReservedQuantity(Math.max(0,
                            inventory.getReservedQuantity() - currentIssue.getQuantity()));
                }
            } else {
                throw new IllegalStateException("Unsupported inventory sync issue type: " + currentIssue.getIssueType());
            }

            inventoryReservationRecordRepository.saveAndFlush(reservation);
            inventoryRepository.saveAndFlush(inventory);
            currentIssue.setStatus("RESOLVED");
            currentIssue.setResolvedAt(LocalDateTime.now());
            currentIssue.setFailureReason(null);
            inventorySyncIssueRepository.save(currentIssue);
        });
    }

    private void resolveIssue(String issueId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                inventorySyncIssueRepository.findById(issueId).ifPresent(issue -> {
                    issue.setStatus("RESOLVED");
                    issue.setResolvedAt(LocalDateTime.now());
                    issue.setFailureReason(null);
                    inventorySyncIssueRepository.save(issue);
                }));
    }

    private void markIssueAttemptFailed(String issueId, RuntimeException failure) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                inventorySyncIssueRepository.findById(issueId).ifPresent(issue -> {
                    int attempts = issue.getAttemptCount() + 1;
                    issue.setAttemptCount(attempts);
                    issue.setStatus(attempts >= 20 ? "FAILED" : "PENDING");
                    issue.setFailureReason(failure.getMessage());
                    inventorySyncIssueRepository.save(issue);
                }));
    }

    private void releaseSeatLock(InventoryReservationRecord reservation) {
        String seatLockKey = "locked_seat:" + reservation.getSeatId();
        Object currentOwner = redisTemplate.opsForValue().get(seatLockKey);
        if (currentOwner != null && currentOwner.toString().equals(reservation.getCustomerId())) {
            redisTemplate.delete(seatLockKey);
        }
    }

    private boolean isMissingReservationMetadata(RuntimeException failure) {
        return failure.getMessage() != null && failure.getMessage().contains("RESERVATION_NOT_FOUND");
    }

    public List<InventoryMismatch> findMismatches() {
        List<InventoryMismatch> mismatches = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (Inventory inventory : inventoryRepository.findAll()) {
            // Skip check if there are active (RESERVED), non-expired reservations in Postgres
            List<InventoryReservationRecord> activeReservations = inventoryReservationRecordRepository.findByProductId(inventory.getProductId())
                    .stream()
                    .filter(r -> "RESERVED".equals(r.getStatus()))
                    .toList();
            if (!activeReservations.isEmpty()) {
                log.info("Skipping inventory reconciliation check for product {} because it has {} active reservation(s)",
                        inventory.getProductId(), activeReservations.size());
                continue;
            }

            String resourceKey = "inventory:" + inventory.getProductId();
            Map<String, Object> redisState = resourceReservationService.getResourceStatus(resourceKey);
            if (redisState.isEmpty()) {
                mismatches.add(InventoryMismatch.missingRedisKey(inventory.getProductId()));
                continue;
            }

            int redisAvailable = toInt(redisState.get("available"));
            int redisReserved = toInt(redisState.get("reserved"));
            if (!inventory.getAvailableQuantity().equals(redisAvailable)
                    || !inventory.getReservedQuantity().equals(redisReserved)) {
                mismatches.add(new InventoryMismatch(
                        inventory.getProductId(),
                        inventory.getAvailableQuantity(),
                        redisAvailable,
                        inventory.getReservedQuantity(),
                        redisReserved,
                        "COUNTER_MISMATCH"
                ));
            }
        }
        return mismatches;
    }

    private int toInt(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(value.toString());
    }

    public record InventoryMismatch(
            String productId,
            Integer postgresAvailable,
            Integer redisAvailable,
            Integer postgresReserved,
            Integer redisReserved,
            String reason
    ) {
        static InventoryMismatch missingRedisKey(String productId) {
            return new InventoryMismatch(productId, null, null, null, null, "MISSING_REDIS_KEY");
        }
    }
}

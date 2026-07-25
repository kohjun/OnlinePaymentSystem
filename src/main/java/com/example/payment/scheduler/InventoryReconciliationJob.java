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

    @Scheduled(fixedDelayString = "${app.inventory.reconciliation.fixed-delay-ms:60000}")
    public void reconcileInventoryCounters() {
        reconcileSyncIssues();
        cleanupExpiredReservations();
        for (InventoryMismatch mismatch : findMismatches()) {
            log.warn("Inventory reconciliation mismatch detected: {}", mismatch);
            try {
                String resourceKey = "inventory:" + mismatch.productId();
                inventoryRepository.findById(mismatch.productId()).ifPresent(inventory -> {
                    log.info("Self-healing: Overwriting Redis status for key {} to match Postgres (total={}, available={}, reserved={})",
                            resourceKey, inventory.getTotalQuantity(), inventory.getAvailableQuantity(), inventory.getReservedQuantity());
                    resourceReservationService.initializeResource(
                            resourceKey,
                            inventory.getTotalQuantity(),
                            inventory.getAvailableQuantity()
                    );
                });
            } catch (Exception e) {
                log.error("Failed to self-heal inventory mismatch for product {}: {}", mismatch.productId(), e.getMessage(), e);
            }
        }
    }

    private void cleanupExpiredReservations() {
        LocalDateTime now = LocalDateTime.now();
        List<InventoryReservationRecord> expired =
                inventoryReservationRecordRepository.findByStatusAndExpiresAtBefore("RESERVED", now);
        for (InventoryReservationRecord reservation : expired) {
            boolean redisReleased = false;
            try {
                redisReleased = resourceReservationService.releaseResource(
                        "inventory:" + reservation.getProductId(),
                        reservation.getQuantity(),
                        reservation.getReservationId()
                );
            } catch (RuntimeException redisFailure) {
                if (!isMissingReservationMetadata(redisFailure)) {
                    log.warn("Expired reservation release will be retried: reservationId={}",
                            reservation.getReservationId(), redisFailure);
                    continue;
                }
                log.warn("Expired reservation metadata was already missing; Postgres will repair Redis counters: reservationId={}",
                        reservation.getReservationId());
            }

            try {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    InventoryReservationRecord current = inventoryReservationRecordRepository
                            .findById(reservation.getReservationId())
                            .orElse(null);
                    if (current == null || !"RESERVED".equals(current.getStatus())) {
                        return;
                    }
                    current.setStatus("CANCELLED");
                    inventoryReservationRecordRepository.saveAndFlush(current);
                    inventoryRepository.findById(current.getProductId()).ifPresent(inventory -> {
                        inventory.setAvailableQuantity(inventory.getAvailableQuantity() + current.getQuantity());
                        inventory.setReservedQuantity(Math.max(0,
                                inventory.getReservedQuantity() - current.getQuantity()));
                        inventoryRepository.saveAndFlush(inventory);
                    });
                });
            } catch (RuntimeException databaseFailure) {
                inventorySyncIssueService.record(
                        reservation.getReservationId(),
                        reservation.getProductId(),
                        reservation.getQuantity(),
                        "RELEASE_DB_APPLY_REQUIRED",
                        databaseFailure
                );
                continue;
            }

            if (reservation.getSeatId() != null) {
                releaseSeatLock(reservation);
            }
            log.info("Expired reservation reconciled: reservationId={}, redisReleased={}",
                    reservation.getReservationId(), redisReleased);
        }
    }

    @Deprecated(forRemoval = true)
    private void cleanupExpiredReservationsLegacy() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            List<InventoryReservationRecord> expired = inventoryReservationRecordRepository.findByStatusAndExpiresAtBefore("RESERVED", now);
            if (expired.isEmpty()) {
                return null;
            }
            log.info("Found {} expired RESERVED reservations to clean up", expired.size());
            for (InventoryReservationRecord reservation : expired) {
                try {
                    reservation.setStatus("CANCELLED");
                    inventoryReservationRecordRepository.save(reservation);
                    
                    inventoryRepository.findById(reservation.getProductId()).ifPresent(inventory -> {
                        inventory.setAvailableQuantity(inventory.getAvailableQuantity() + reservation.getQuantity());
                        inventory.setReservedQuantity(Math.max(0, inventory.getReservedQuantity() - reservation.getQuantity()));
                        inventoryRepository.save(inventory);
                    });

                    // Redis inventory release
                    String resourceKey = "inventory:" + reservation.getProductId();
                    resourceReservationService.releaseResource(resourceKey, reservation.getQuantity(), reservation.getReservationId());

                    // Redis seat lock release
                    if (reservation.getSeatId() != null) {
                        String seatLockKey = "locked_seat:" + reservation.getSeatId();
                        Object currentOwner = redisTemplate.opsForValue().get(seatLockKey);
                        if (currentOwner != null && currentOwner.toString().equals(reservation.getCustomerId())) {
                            redisTemplate.delete(seatLockKey);
                            log.info("Released seat lock in Redis for expired reservation: seatId={}, customerId={}",
                                    reservation.getSeatId(), reservation.getCustomerId());
                        }
                    }

                    log.info("Expired reservation {} cancelled. Recovered stock: product={}, qty={}",
                            reservation.getReservationId(), reservation.getProductId(), reservation.getQuantity());
                } catch (Exception e) {
                    log.error("Failed to clean up expired reservation {}: {}", reservation.getReservationId(), e.getMessage(), e);
                }
            }
            return null;
        });
    }

    private void reconcileSyncIssues() {
        List<InventorySyncIssue> issues = inventorySyncIssueRepository
                .findByStatusOrderByCreatedAtAsc("PENDING", PageRequest.of(0, 50));
        for (InventorySyncIssue issue : issues) {
            try {
                if ("RESERVE_RELEASE_REQUIRED".equals(issue.getIssueType())) {
                    resourceReservationService.releaseResource(
                            "inventory:" + issue.getProductId(),
                            issue.getQuantity(),
                            issue.getReservationId()
                    );
                    resolveIssue(issue.getIssueId());
                } else {
                    applyDatabaseRepair(issue);
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
                    .findById(currentIssue.getReservationId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Inventory reservation not found: " + currentIssue.getReservationId()));
            Inventory inventory = inventoryRepository.findById(currentIssue.getProductId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Inventory not found: " + currentIssue.getProductId()));

            if ("CONFIRM_DB_APPLY_REQUIRED".equals(currentIssue.getIssueType())) {
                if ("RESERVED".equals(reservation.getStatus())) {
                    reservation.setStatus("CONFIRMED");
                    inventory.setReservedQuantity(Math.max(0,
                            inventory.getReservedQuantity() - currentIssue.getQuantity()));
                }
            } else if ("RELEASE_DB_APPLY_REQUIRED".equals(currentIssue.getIssueType())) {
                if (!"CANCELLED".equals(reservation.getStatus())) {
                    if ("RESERVED".equals(reservation.getStatus())) {
                        inventory.setReservedQuantity(Math.max(0,
                                inventory.getReservedQuantity() - currentIssue.getQuantity()));
                    }
                    inventory.setAvailableQuantity(
                            inventory.getAvailableQuantity() + currentIssue.getQuantity());
                    reservation.setStatus("CANCELLED");
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
                    .filter(r -> "RESERVED".equals(r.getStatus()) && r.getExpiresAt().isAfter(now))
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

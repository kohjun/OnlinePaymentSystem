package com.example.payment.application.service;

import com.example.payment.domain.entity.InventoryReservationRecord;
import com.example.payment.domain.model.inventory.Inventory;
import com.example.payment.domain.repository.InventoryRepository;
import com.example.payment.domain.repository.InventoryReservationRecordRepository;
import com.example.payment.infrastructure.util.ResourceReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryHoldService {

    private final ResourceReservationService resourceReservationService;
    private final InventoryRepository inventoryRepository;
    private final InventoryReservationRecordRepository reservationRepository;
    private final InventorySyncIssueService inventorySyncIssueService;
    private final InventoryReleasePolicy releasePolicy;
    private final com.example.payment.domain.repository.InventorySyncIssueRepository syncIssues;
    private final ReservationAuthorizationFence authorizationFence;

    @Transactional
    public InventoryReservationRecord hold(String reservationId, String productId, String customerId,
                                           int quantity, LocalDateTime expiresAt, String origin,
                                           String sourceType, String sourceId) {
        InventoryReservationRecord existing = reservationRepository.findByIdForUpdate(reservationId).orElse(null);
        if (existing != null) {
            validateExisting(existing, productId, customerId, quantity);
            if ("RESERVED".equals(existing.getStatus()) || "CONFIRMED".equals(existing.getStatus())) {
                return existing;
            }
            throw new IllegalStateException("INVENTORY_HOLD_NOT_ACTIVE: " + reservationId);
        }

        Duration ttl = Duration.between(LocalDateTime.now(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("Inventory hold expiry must be in the future.");
        }
        boolean reserved = resourceReservationService.reserveResource(
                inventoryKey(productId), quantity, ttl, reservationId);
        if (!reserved) {
            throw new InsufficientInventoryException(productId, quantity);
        }
        releaseRedisOnRollback(reservationId, productId, quantity);

        try {
            Inventory inventory = inventoryRepository.findByIdForUpdate(productId)
                    .orElseThrow(() -> new IllegalStateException("Inventory not found: " + productId));
            if (inventory.getAvailableQuantity() < quantity) {
                throw new InsufficientInventoryException(productId, quantity);
            }
            inventory.setAvailableQuantity(inventory.getAvailableQuantity() - quantity);
            inventory.setReservedQuantity(inventory.getReservedQuantity() + quantity);
            inventoryRepository.saveAndFlush(inventory);

            return reservationRepository.saveAndFlush(InventoryReservationRecord.builder()
                    .reservationId(reservationId)
                    .productId(productId)
                    .customerId(customerId)
                    .quantity(quantity)
                    .status("RESERVED")
                    .authorizationState("NOT_STARTED")
                    .origin(origin)
                    .sourceType(sourceType)
                    .sourceId(sourceId)
                    .expiresAt(expiresAt)
                    .createdAt(LocalDateTime.now())
                    .build());
        } catch (RuntimeException failure) {
            inventorySyncIssueService.record(reservationId, productId, quantity,
                    "HOLD_DB_APPLY_REQUIRED", failure);
            throw failure;
        }
    }

    @Transactional
    public InventoryReservationRecord claim(String reservationId, String intentId,
                                            String productId, String customerId, int quantity) {
        InventoryReservationRecord reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new IllegalStateException("INVENTORY_HOLD_NOT_FOUND: " + reservationId));
        validateExisting(reservation, productId, customerId, quantity);
        if (!"RESERVED".equals(reservation.getStatus())) {
            throw new IllegalStateException("INVENTORY_HOLD_NOT_ACTIVE: " + reservationId);
        }
        if (!reservation.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new IllegalStateException("INVENTORY_HOLD_EXPIRED: " + reservationId);
        }
        if (reservation.getClaimedIntentId() != null
                && !reservation.getClaimedIntentId().equals(intentId)) {
            throw new IllegalStateException("INVENTORY_HOLD_ALREADY_CLAIMED: " + reservationId);
        }
        reservation.setClaimedIntentId(intentId);
        return reservationRepository.save(reservation);
    }

    @Transactional
    public InventoryReservationRecord confirm(String reservationId, String orderId, String paymentId) {
        InventoryReservationRecord reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new IllegalStateException("INVENTORY_HOLD_NOT_FOUND: " + reservationId));
        if ("CONFIRMED".equals(reservation.getStatus())) {
            return reservation;
        }
        if (!"RESERVED".equals(reservation.getStatus())) {
            throw new IllegalStateException("INVENTORY_HOLD_NOT_ACTIVE: " + reservationId);
        }
        if (!resourceReservationService.confirmResource(
                inventoryKey(reservation.getProductId()), reservation.getQuantity(), reservationId)) {
            throw new IllegalStateException("Inventory confirmation requires reconciliation");
        }
        try {
            reservation.setStatus("CONFIRMED");
            reservation.setOrderId(orderId);
            reservation.setPaymentId(paymentId);
            reservationRepository.saveAndFlush(reservation);

            Inventory inventory = inventoryRepository.findByIdForUpdate(reservation.getProductId())
                    .orElseThrow(() -> new IllegalStateException("Inventory not found: " + reservation.getProductId()));
            inventory.setReservedQuantity(Math.max(0,
                    inventory.getReservedQuantity() - reservation.getQuantity()));
            inventoryRepository.saveAndFlush(inventory);
            return reservation;
        } catch (RuntimeException failure) {
            inventorySyncIssueService.record(reservationId, reservation.getProductId(), reservation.getQuantity(),
                    "CONFIRM_DB_APPLY_REQUIRED", failure);
            throw failure;
        }
    }

    @Transactional
    public boolean release(String reservationId, String terminalStatus) {
        InventoryReservationRecord reservation = reservationRepository.findByIdForUpdate(reservationId).orElse(null);
        if (reservation == null || !"RESERVED".equals(reservation.getStatus()) || !releasePolicy.mayRelease(reservation)) {
            return false;
        }
        return releaseLocked(reservation, terminalStatus);
    }

    @Transactional
    public boolean releaseForOfferExpiry(String reservationId) {
        if (reservationId == null || reservationId.isBlank()) {
            return false;
        }
        InventoryReservationRecord reservation = reservationRepository.findByIdForUpdate(reservationId).orElse(null);
        if (reservation == null || "CONFIRMED".equals(reservation.getStatus())) {
            return false;
        }
        if (!"RESERVED".equals(reservation.getStatus())) {
            return "EXPIRED".equals(reservation.getStatus()) || "CANCELLED".equals(reservation.getStatus());
        }
        if (!releasePolicy.mayRelease(reservation)) {
            return false;
        }
        return releaseLocked(reservation, "EXPIRED");
    }

    @Transactional
    public boolean compensate(String reservationId) {
        var reservation = reservationRepository.findByIdForUpdate(reservationId).orElse(null);
        return compensateLocked(reservation);
    }

    @Transactional
    public boolean compensate(com.example.payment.application.temporal.ReservationWorkflowCommand command) {
        var reservation = reservationRepository.findByIdForUpdate(command.getReservationId()).orElse(null);
        if (reservation != null) authorizationFence.closeBeforeAuthorization(reservation, command);
        return compensateLocked(reservation);
    }

    private boolean compensateLocked(InventoryReservationRecord reservation) {
        if (reservation == null || java.util.Set.of("CANCELLED", "CANCELED", "EXPIRED", "RELEASED").contains(reservation.getStatus())) return false;
        if (!releasePolicy.mayRelease(reservation)) throw new IllegalStateException("Payment verification blocks inventory release");
        return releaseLocked(reservation, "CANCELLED");
    }

    private boolean releaseLocked(InventoryReservationRecord reservation, String terminalStatus) {
        String reservationId = reservation.getReservationId();
        boolean wasReserved = "RESERVED".equals(reservation.getStatus());
        reservation.setStatus(terminalStatus);
        reservationRepository.saveAndFlush(reservation);
        Inventory inventory = inventoryRepository.findByIdForUpdate(reservation.getProductId()).orElseThrow();
        if (wasReserved && inventory.getReservedQuantity() < reservation.getQuantity()) {
            throw new IllegalStateException("Inventory reserved count mismatch");
        }
        inventory.setAvailableQuantity(inventory.getAvailableQuantity() + reservation.getQuantity());
        if (wasReserved) inventory.setReservedQuantity(inventory.getReservedQuantity() - reservation.getQuantity());
        inventoryRepository.saveAndFlush(inventory);
        syncIssues.save(com.example.payment.domain.entity.InventorySyncIssue.builder()
                .issueId("RELEASE-" + java.util.UUID.nameUUIDFromBytes(reservationId.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .reservationId(reservationId).productId(reservation.getProductId()).quantity(reservation.getQuantity())
                .issueType("RELEASE_REDIS_REQUIRED").status("PENDING").attemptCount(0).build());
        // The durable projection commits with the DB release. Redis is only touched after commit.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    try {
                        resourceReservationService.releaseResource(inventoryKey(reservation.getProductId()),
                                reservation.getQuantity(), reservationId);
                    } catch (RuntimeException failure) {
                        log.warn("Redis release queued: reservationId={}", reservationId);
                    }
                }
            });
        }
        return true;
    }

    private void validateExisting(InventoryReservationRecord reservation, String productId,
                                  String customerId, int quantity) {
        if (!productId.equals(reservation.getProductId())
                || !customerId.equals(reservation.getCustomerId())
                || quantity != reservation.getQuantity()) {
            throw new IllegalStateException("INVENTORY_HOLD_CONFLICT: " + reservation.getReservationId());
        }
    }

    private void releaseRedisOnRollback(String reservationId, String productId, int quantity) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_ROLLED_BACK) {
                    return;
                }
                try {
                    resourceReservationService.releaseResource(inventoryKey(productId), quantity, reservationId);
                } catch (RuntimeException failure) {
                    log.error("Failed to release Redis hold after transaction rollback: reservationId={}",
                            reservationId, failure);
                    inventorySyncIssueService.record(reservationId, productId, quantity,
                            "ROLLBACK_RELEASE_REQUIRED", failure);
                }
            }
        });
    }

    private String inventoryKey(String productId) {
        return "inventory:" + productId;
    }

    public static class InsufficientInventoryException extends RuntimeException {
        public InsufficientInventoryException(String productId, int quantity) {
            super("INSUFFICIENT_STOCK: productId=" + productId + ", quantity=" + quantity);
        }
    }
}

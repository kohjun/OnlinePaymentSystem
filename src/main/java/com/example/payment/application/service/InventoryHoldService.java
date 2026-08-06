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
        resourceReservationService.confirmResource(
                inventoryKey(reservation.getProductId()), reservation.getQuantity(), reservationId);
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
        if (reservation == null || !"RESERVED".equals(reservation.getStatus())) {
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
        if (reservation.getClaimedIntentId() != null) {
            return false;
        }
        return releaseLocked(reservation, "EXPIRED");
    }

    private boolean releaseLocked(InventoryReservationRecord reservation, String terminalStatus) {
        String reservationId = reservation.getReservationId();
        resourceReservationService.releaseResource(
                inventoryKey(reservation.getProductId()), reservation.getQuantity(), reservationId);
        try {
            reservation.setStatus(terminalStatus);
            reservationRepository.saveAndFlush(reservation);
            Inventory inventory = inventoryRepository.findByIdForUpdate(reservation.getProductId())
                    .orElseThrow(() -> new IllegalStateException("Inventory not found: " + reservation.getProductId()));
            inventory.setAvailableQuantity(inventory.getAvailableQuantity() + reservation.getQuantity());
            inventory.setReservedQuantity(Math.max(0,
                    inventory.getReservedQuantity() - reservation.getQuantity()));
            inventoryRepository.saveAndFlush(inventory);
            return true;
        } catch (RuntimeException failure) {
            inventorySyncIssueService.record(reservationId, reservation.getProductId(), reservation.getQuantity(),
                    "RELEASE_DB_APPLY_REQUIRED", failure);
            throw failure;
        }
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

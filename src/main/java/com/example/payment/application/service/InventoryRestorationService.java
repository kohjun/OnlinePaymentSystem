package com.example.payment.application.service;

import com.example.payment.domain.entity.*;
import com.example.payment.domain.model.marketplace.MarketplaceOrder;
import com.example.payment.domain.repository.*;
import com.example.payment.infrastructure.util.ResourceReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.LocalDateTime;
import java.util.UUID;

@Service @RequiredArgsConstructor
public class InventoryRestorationService {
    private final InventoryMovementRepository movementRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryReservationRecordRepository reservationRepository;
    private final ResourceReservationService resourceReservationService;
    private final InventorySyncIssueRepository syncIssues;

    @Transactional
    public InventoryMovement restoreCancelledOrder(MarketplaceOrder order, String operationKey) {
        InventoryReservationRecord reservation = order.getReservationId() == null ? null
                : reservationRepository.findByIdForUpdate(order.getReservationId()).orElse(null);
        var inventory = inventoryRepository.findByIdForUpdate(order.getProductId()).orElseThrow();
        var existing = movementRepository.findByOperationKey(operationKey).orElse(null);
        if (existing != null) return existing;
        if (reservation != null && "RELEASED".equals(reservation.getStatus())) {
            throw new IllegalStateException("Inventory was already restored with another operation");
        }
        inventory.setAvailableQuantity(inventory.getAvailableQuantity() + order.getQuantity());
        if (inventory.getAvailableQuantity() > inventory.getTotalQuantity()) throw new IllegalStateException("Inventory restoration exceeds total stock");
        inventoryRepository.save(inventory);
        if (reservation != null) {
            reservation.setStatus("RELEASED");
            reservationRepository.save(reservation);
        }
        var movement = movementRepository.saveAndFlush(InventoryMovement.builder().movementId("INVMOV-" + UUID.randomUUID())
                .operationKey(operationKey).marketplaceOrderId(order.getMarketplaceOrderId())
                .reservationId(order.getReservationId()).productId(order.getProductId()).quantity(order.getQuantity())
                .type("ORDER_CANCELLATION_RESTORE").status("APPLIED").createdAt(LocalDateTime.now()).build());
        syncIssues.save(InventorySyncIssue.builder().issueId("RESTORE-" + UUID.randomUUID())
                .reservationId(operationKey).productId(order.getProductId()).quantity(order.getQuantity())
                .issueType("RESTORE_REDIS_REQUIRED").status("PENDING").attemptCount(0).build());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    try { resourceReservationService.restoreConfirmedResource("inventory:" + order.getProductId(), order.getQuantity(), operationKey); }
                    catch (RuntimeException ignored) { /* The durable projection remains pending. */ }
                }
            });
        }
        return movement;
    }
}

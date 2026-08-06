package com.example.payment.application.service;

import com.example.payment.domain.entity.InventoryMovement;
import com.example.payment.domain.entity.InventoryReservationRecord;
import com.example.payment.domain.model.inventory.Inventory;
import com.example.payment.domain.model.marketplace.MarketplaceOrder;
import com.example.payment.domain.repository.InventoryMovementRepository;
import com.example.payment.domain.repository.InventoryRepository;
import com.example.payment.domain.repository.InventoryReservationRecordRepository;
import com.example.payment.infrastructure.util.IdGenerator;
import com.example.payment.infrastructure.util.ResourceReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class InventoryRestorationService {

    private final InventoryMovementRepository movementRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryReservationRecordRepository reservationRepository;
    private final ResourceReservationService resourceReservationService;
    private final InventorySyncIssueService inventorySyncIssueService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public InventoryMovement restoreCancelledOrder(MarketplaceOrder order, String operationKey) {
        InventoryMovement existing = movementRepository.findByOperationKey(operationKey).orElse(null);
        if (existing != null) {
            return existing;
        }

        String reservationId = order.getReservationId() != null
                ? order.getReservationId()
                : "ORDER-" + order.getMarketplaceOrderId();
        try {
            resourceReservationService.restoreConfirmedResource(
                    "inventory:" + order.getProductId(), order.getQuantity(), operationKey);

            Inventory inventory = inventoryRepository.findByIdForUpdate(order.getProductId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Inventory not found: " + order.getProductId()));
            int restoredAvailable = Math.min(
                    inventory.getTotalQuantity(),
                    inventory.getAvailableQuantity() + order.getQuantity());
            inventory.setAvailableQuantity(restoredAvailable);
            inventoryRepository.saveAndFlush(inventory);

            InventoryReservationRecord reservation = order.getReservationId() != null
                    ? reservationRepository.findByIdForUpdate(order.getReservationId()).orElse(null)
                    : null;
            if (reservation != null && "CONFIRMED".equals(reservation.getStatus())) {
                reservation.setStatus("RELEASED");
                reservationRepository.saveAndFlush(reservation);
            }

            return movementRepository.saveAndFlush(InventoryMovement.builder()
                    .movementId("INVMOV-" + IdGenerator.generateEventId())
                    .operationKey(operationKey)
                    .marketplaceOrderId(order.getMarketplaceOrderId())
                    .reservationId(order.getReservationId())
                    .productId(order.getProductId())
                    .quantity(order.getQuantity())
                    .type("ORDER_CANCELLATION_RESTORE")
                    .status("APPLIED")
                    .createdAt(LocalDateTime.now())
                    .build());
        } catch (RuntimeException failure) {
            inventorySyncIssueService.record(
                    reservationId,
                    order.getProductId(),
                    order.getQuantity(),
                    "ORDER_CANCELLATION_RESTORE_REQUIRED",
                    failure
            );
            throw failure;
        }
    }
}

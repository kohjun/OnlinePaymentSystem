package com.example.payment.application.service;

import com.example.payment.domain.entity.InventoryMovement;
import com.example.payment.domain.entity.InventoryReservationRecord;
import com.example.payment.domain.model.inventory.Inventory;
import com.example.payment.domain.model.marketplace.MarketplaceOrder;
import com.example.payment.domain.repository.InventoryMovementRepository;
import com.example.payment.domain.repository.InventoryRepository;
import com.example.payment.domain.repository.InventoryReservationRecordRepository;
import com.example.payment.infrastructure.util.ResourceReservationService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InventoryRestorationServiceTest {

    @Test
    void restoresConfirmedInventoryOnceWithOperationLedger() {
        InventoryMovementRepository movementRepository = mock(InventoryMovementRepository.class);
        InventoryRepository inventoryRepository = mock(InventoryRepository.class);
        InventoryReservationRecordRepository reservationRepository =
                mock(InventoryReservationRecordRepository.class);
        ResourceReservationService resourceService = mock(ResourceReservationService.class);
        InventorySyncIssueService issueService = mock(InventorySyncIssueService.class);
        InventoryRestorationService service = new InventoryRestorationService(
                movementRepository, inventoryRepository, reservationRepository, resourceService, issueService);
        MarketplaceOrder order = MarketplaceOrder.builder()
                .marketplaceOrderId("MORD-1")
                .productId("PROD-1")
                .reservationId("RES-1")
                .quantity(1)
                .build();
        Inventory inventory = Inventory.builder()
                .productId("PROD-1")
                .totalQuantity(10)
                .availableQuantity(4)
                .reservedQuantity(0)
                .build();
        InventoryReservationRecord reservation = InventoryReservationRecord.builder()
                .reservationId("RES-1")
                .status("CONFIRMED")
                .build();
        when(movementRepository.findByOperationKey("OP-1")).thenReturn(Optional.empty());
        when(inventoryRepository.findByIdForUpdate("PROD-1")).thenReturn(Optional.of(inventory));
        when(reservationRepository.findByIdForUpdate("RES-1")).thenReturn(Optional.of(reservation));
        when(movementRepository.saveAndFlush(any(InventoryMovement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        InventoryMovement result = service.restoreCancelledOrder(order, "OP-1");

        assertEquals(5, inventory.getAvailableQuantity());
        assertEquals("RELEASED", reservation.getStatus());
        assertEquals("APPLIED", result.getStatus());
        verify(resourceService).restoreConfirmedResource("inventory:PROD-1", 1, "OP-1");
    }
}

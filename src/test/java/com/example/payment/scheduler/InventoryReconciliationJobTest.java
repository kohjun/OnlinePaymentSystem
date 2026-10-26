package com.example.payment.scheduler;

import com.example.payment.domain.model.inventory.Inventory;
import com.example.payment.domain.repository.InventoryRepository;
import com.example.payment.infrastructure.util.ResourceReservationService;
import org.junit.jupiter.api.Test;

import com.example.payment.domain.repository.InventoryReservationRecordRepository;
import com.example.payment.domain.repository.InventorySyncIssueRepository;
import com.example.payment.application.service.InventorySyncIssueService;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.data.redis.core.RedisTemplate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InventoryReconciliationJobTest {

    private final InventoryRepository inventoryRepository = mock(InventoryRepository.class);
    private final ResourceReservationService resourceReservationService = mock(ResourceReservationService.class);
    private final InventoryReservationRecordRepository inventoryReservationRecordRepository = mock(InventoryReservationRecordRepository.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
    private final InventorySyncIssueRepository inventorySyncIssueRepository = mock(InventorySyncIssueRepository.class);
    private final InventorySyncIssueService inventorySyncIssueService = mock(InventorySyncIssueService.class);
    private final com.example.payment.application.service.InventoryHoldService holds = mock(com.example.payment.application.service.InventoryHoldService.class);
    private final InventoryReconciliationJob job = new InventoryReconciliationJob(
            inventoryRepository,
            resourceReservationService,
            inventoryReservationRecordRepository,
            transactionManager,
            redisTemplate,
            inventorySyncIssueRepository,
            inventorySyncIssueService,
            holds,
            mock(com.example.payment.application.service.InventoryReleasePolicy.class)
    );

    @Test
    void legacyReleaseRepairDelegatesToFencedReleaseWithoutWritingCountersAgain() {
        var issue = com.example.payment.domain.entity.InventorySyncIssue.builder()
                .issueId("ISSUE-1").reservationId("HOLD-1").productId("PROD-001")
                .quantity(1).issueType("RELEASE_DB_APPLY_REQUIRED").status("PENDING").build();
        when(inventorySyncIssueRepository.findById("ISSUE-1")).thenReturn(java.util.Optional.of(issue));
        when(inventoryReservationRecordRepository.findByIdForUpdate("HOLD-1"))
                .thenReturn(java.util.Optional.of(com.example.payment.domain.entity.InventoryReservationRecord.builder().build()));
        when(transactionManager.getTransaction(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        when(holds.compensate("HOLD-1")).thenReturn(false);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(job, "applyDatabaseRepair", issue);
        assertEquals("RESOLVED", issue.getStatus());
        org.mockito.Mockito.verify(holds).compensate("HOLD-1");
        org.mockito.Mockito.verifyNoInteractions(inventoryRepository, resourceReservationService);
    }

    @Test
    void legacyRepairDoesNotResolveAnUncertainPayment() {
        var issue = com.example.payment.domain.entity.InventorySyncIssue.builder()
                .issueId("ISSUE-2").reservationId("HOLD-2").issueType("RELEASE_DB_APPLY_REQUIRED")
                .status("PENDING").build();
        when(inventorySyncIssueRepository.findById("ISSUE-2")).thenReturn(java.util.Optional.of(issue));
        when(inventoryReservationRecordRepository.findByIdForUpdate("HOLD-2"))
                .thenReturn(java.util.Optional.of(com.example.payment.domain.entity.InventoryReservationRecord.builder().build()));
        when(transactionManager.getTransaction(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        when(holds.compensate("HOLD-2")).thenThrow(new IllegalStateException("Payment verification blocks release"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(job, "applyDatabaseRepair", issue));
        assertEquals("PENDING", issue.getStatus());
        org.mockito.Mockito.verify(inventorySyncIssueRepository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void findMismatches_detectsCounterMismatch() {
        Inventory inventory = Inventory.builder()
                .productId("PROD-001")
                .totalQuantity(10)
                .availableQuantity(7)
                .reservedQuantity(3)
                .build();

        when(inventoryRepository.findAll()).thenReturn(List.of(inventory));
        when(resourceReservationService.getResourceStatus("inventory:PROD-001"))
                .thenReturn(Map.of("total", 10, "available", 8, "reserved", 2));

        List<InventoryReconciliationJob.InventoryMismatch> mismatches = job.findMismatches();

        assertEquals(1, mismatches.size());
        assertEquals("PROD-001", mismatches.get(0).productId());
        assertEquals("COUNTER_MISMATCH", mismatches.get(0).reason());
    }

    @Test
    void findMismatches_detectsMissingRedisKey() {
        Inventory inventory = Inventory.builder()
                .productId("PROD-002")
                .totalQuantity(10)
                .availableQuantity(10)
                .reservedQuantity(0)
                .build();

        when(inventoryRepository.findAll()).thenReturn(List.of(inventory));
        when(resourceReservationService.getResourceStatus("inventory:PROD-002")).thenReturn(Map.of());

        List<InventoryReconciliationJob.InventoryMismatch> mismatches = job.findMismatches();

        assertEquals(1, mismatches.size());
        assertEquals("MISSING_REDIS_KEY", mismatches.get(0).reason());
    }
}

package com.example.payment.scheduler;

import com.example.payment.domain.model.inventory.Inventory;
import com.example.payment.domain.repository.InventoryRepository;
import com.example.payment.infrastructure.util.ResourceReservationService;
import org.junit.jupiter.api.Test;

import com.example.payment.domain.repository.InventoryReservationRecordRepository;
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
    private final InventoryReconciliationJob job = new InventoryReconciliationJob(
            inventoryRepository,
            resourceReservationService,
            inventoryReservationRecordRepository,
            transactionManager,
            redisTemplate
    );

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

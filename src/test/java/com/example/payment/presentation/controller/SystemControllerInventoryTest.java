package com.example.payment.presentation.controller;

import com.example.payment.application.service.DistributionReadinessService;
import com.example.payment.infrastructure.persistence.redis.repository.CacheService;
import com.example.payment.infrastructure.security.AuthorizationGuard;
import com.example.payment.infrastructure.security.SecurityAuditService;
import com.example.payment.scheduler.InventoryReconciliationJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemControllerInventoryTest {

    @Mock CacheService cacheService;
    @Mock DistributionReadinessService distributionReadinessService;
    @Mock AuthorizationGuard authorizationGuard;
    @Mock SecurityAuditService securityAuditService;
    @Mock InventoryReconciliationJob inventoryReconciliationJob;

    @InjectMocks SystemController controller;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "inventoryReconciliationJob", inventoryReconciliationJob);
    }

    @Test
    void inspectionReportsMismatchesWithoutRepairingInventory() {
        when(inventoryReconciliationJob.findMismatches()).thenReturn(List.of(
                new InventoryReconciliationJob.InventoryMismatch("PROD-1", 4, 3, 1, 2, "COUNTER_MISMATCH")
        ));

        ResponseEntity<Map<String, Object>> response = controller.inspectReconciliation();

        assertEquals(200, response.getStatusCode().value());
        assertEquals("MISMATCH_DETECTED", response.getBody().get("status"));
        assertEquals(1, response.getBody().get("mismatchCount"));
        verify(authorizationGuard).requireAdmin();
        verify(inventoryReconciliationJob).findMismatches();
    }
}

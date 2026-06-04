package com.example.payment.scheduler;

import com.example.payment.domain.model.inventory.Inventory;
import com.example.payment.domain.repository.InventoryRepository;
import com.example.payment.infrastructure.util.ResourceReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.inventory.reconciliation.enabled", havingValue = "true")
public class InventoryReconciliationJob {

    private final InventoryRepository inventoryRepository;
    private final ResourceReservationService resourceReservationService;

    @Scheduled(fixedDelayString = "${app.inventory.reconciliation.fixed-delay-ms:60000}")
    public void reconcileInventoryCounters() {
        for (Inventory inventory : inventoryRepository.findAll()) {
            String resourceKey = "inventory:" + inventory.getProductId();
            Map<String, Object> redisState = resourceReservationService.getResourceStatus(resourceKey);
            if (redisState.isEmpty()) {
                log.warn("Inventory reconciliation missing Redis key: productId={}, key={}",
                        inventory.getProductId(), resourceKey);
                continue;
            }

            int redisAvailable = toInt(redisState.get("available"));
            int redisReserved = toInt(redisState.get("reserved"));
            if (!inventory.getAvailableQuantity().equals(redisAvailable)
                    || !inventory.getReservedQuantity().equals(redisReserved)) {
                log.warn("Inventory reconciliation mismatch: productId={}, postgresAvailable={}, redisAvailable={}, postgresReserved={}, redisReserved={}",
                        inventory.getProductId(),
                        inventory.getAvailableQuantity(),
                        redisAvailable,
                        inventory.getReservedQuantity(),
                        redisReserved);
            }
        }
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
}

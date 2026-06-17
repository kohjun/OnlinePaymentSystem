package com.example.payment.scheduler;

import com.example.payment.domain.model.inventory.Inventory;
import com.example.payment.domain.repository.InventoryRepository;
import com.example.payment.domain.repository.InventoryReservationRecordRepository;
import com.example.payment.domain.entity.InventoryReservationRecord;
import com.example.payment.infrastructure.util.ResourceReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.redis.core.RedisTemplate;

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

    @Scheduled(fixedDelayString = "${app.inventory.reconciliation.fixed-delay-ms:60000}")
    public void reconcileInventoryCounters() {
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

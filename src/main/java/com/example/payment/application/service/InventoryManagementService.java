/**
 * ========================================
 * 4. InventoryManagementService (정합성 관리 전담)
 * ========================================
 */
package com.example.payment.application.service;

import com.example.payment.domain.model.inventory.Inventory;
import com.example.payment.domain.model.inventory.Reservation;
import com.example.payment.domain.repository.InventoryRepository;
import com.example.payment.domain.repository.ReservationRepository;
import com.example.payment.infrastructure.persistance.redis.repository.CacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class InventoryManagementService {

    private final InventoryRepository inventoryRepository;
    private final ReservationRepository reservationRepository;
    private final CacheService cacheService;

    /**
     * Redis와 MySQL 간 재고 정합성 검증 (5분마다)
     */
    @Scheduled(fixedRate = 300000)
    public void verifyInventoryConsistency() {
        log.info("Starting inventory consistency verification");

        try {
            List<Inventory> allInventory = inventoryRepository.findAll();
            int totalChecked = 0;
            int mismatches = 0;

            for (Inventory dbInventory : allInventory) {
                String productId = dbInventory.getProductId();
                String redisKey = "inventory:" + productId;

                Object redisData = cacheService.getCachedData(redisKey);

                if (redisData == null) {
                    // Redis에 데이터가 없으면 동기화
                    syncInventoryToRedis(productId);
                    log.info("Synced missing inventory data to Redis: {}", productId);
                } else {
                    // 데이터 일치성 확인
                    @SuppressWarnings("unchecked")
                    Map<String, Object> redisInventory = (Map<String, Object>) redisData;

                    Integer redisAvailable = (Integer) redisInventory.get("available_quantity");
                    Integer redisReserved = (Integer) redisInventory.get("reserved_quantity");

                    if (!dbInventory.getAvailableQuantity().equals(redisAvailable) ||
                            !dbInventory.getReservedQuantity().equals(redisReserved)) {

                        mismatches++;
                        log.warn("Inventory mismatch detected for product {}: " +
                                        "DB(available={}, reserved={}) vs Redis(available={}, reserved={})",
                                productId, dbInventory.getAvailableQuantity(), dbInventory.getReservedQuantity(),
                                redisAvailable, redisReserved);

                        // MySQL을 정답으로 간주하고 Redis 업데이트
                        syncInventoryToRedis(productId);
                    }
                }

                totalChecked++;
            }

            log.info("Inventory consistency check completed: {} products checked, {} mismatches corrected",
                    totalChecked, mismatches);

        } catch (Exception e) {
            log.error("Error during inventory consistency verification: {}", e.getMessage(), e);
        }
    }

    /**
     * 만료된 예약 정리 (1분마다)
     */
    @Scheduled(fixedRate = 60000)
    @Transactional
    public void cleanupExpiredReservations() {
        try {
            List<Reservation> expiredReservations = reservationRepository.findExpiredReservations(LocalDateTime.now());

            for (Reservation reservation : expiredReservations) {
                if (reservation.getStatus() == Reservation.ReservationStatus.PENDING) {
                    log.info("Processing expired reservation: {}", reservation.getId());

                    // 예약 상태를 만료로 변경
                    reservation.setStatus(Reservation.ReservationStatus.EXPIRED);
                    reservationRepository.save(reservation);

                    // Redis에서도 정리 (실패해도 계속 진행)
                    try {
                        String cacheKey = "reservation-state:" + reservation.getId();
                        cacheService.deleteCache(cacheKey);
                    } catch (Exception e) {
                        log.warn("Failed to cleanup reservation cache: reservationId={}", reservation.getId());
                    }

                    // 재고를 다시 사용 가능하도록 복원
                    restoreInventoryFromExpiredReservation(reservation);

                    log.info("Expired reservation cleaned up: {}", reservation.getId());
                }
            }

            if (!expiredReservations.isEmpty()) {
                log.info("Cleaned up {} expired reservations", expiredReservations.size());
            }

        } catch (Exception e) {
            log.error("Error during expired reservation cleanup: {}", e.getMessage(), e);
        }
    }

    /**
     * 만료된 예약으로부터 재고 복원
     */
    private void restoreInventoryFromExpiredReservation(Reservation reservation) {
        try {
            Optional<Inventory> inventoryOpt = inventoryRepository.findById(reservation.getProductId());
            if (inventoryOpt.isPresent()) {
                Inventory inventory = inventoryOpt.get();

                // 예약 수량을 사용 가능 재고로 복원
                inventory.setReservedQuantity(inventory.getReservedQuantity() - reservation.getQuantity());
                inventory.setAvailableQuantity(inventory.getAvailableQuantity() + reservation.getQuantity());

                inventoryRepository.save(inventory);

                // Redis 동기화
                syncInventoryToRedis(reservation.getProductId());

                log.info("Inventory restored from expired reservation: productId={}, quantity={}",
                        reservation.getProductId(), reservation.getQuantity());
            }
        } catch (Exception e) {
            log.error("Error restoring inventory from expired reservation: reservationId={}",
                    reservation.getId(), e);
        }
    }

    /**
     * MySQL의 재고 정보를 Redis에 동기화
     */
    private void syncInventoryToRedis(String productId) {
        try {
            Optional<Inventory> inventoryOpt = inventoryRepository.findById(productId);
            if (inventoryOpt.isEmpty()) {
                log.warn("Product not found for Redis sync: {}", productId);
                return;
            }

            Inventory inventory = inventoryOpt.get();

            Map<String, Object> inventoryData = new HashMap<>();
            inventoryData.put("total_quantity", inventory.getTotalQuantity());
            inventoryData.put("available_quantity", inventory.getAvailableQuantity());
            inventoryData.put("reserved_quantity", inventory.getReservedQuantity());
            inventoryData.put("version", inventory.getVersion());
            inventoryData.put("last_updated", System.currentTimeMillis());

            String redisKey = "inventory:" + productId;
            cacheService.cacheMapData(redisKey, inventoryData, 3600); // 1시간 TTL

            log.debug("Synced inventory to Redis: {} -> {}", productId, inventoryData);

        } catch (Exception e) {
            log.error("Error syncing inventory to Redis for product {}: {}", productId, e.getMessage(), e);
        }
    }

    /**
     * 특정 상품의 현재 재고 상태 조회
     */
    public Map<String, Object> getInventoryStatus(String productId) {
        try {
            // 먼저 Redis에서 조회
            String redisKey = "inventory:" + productId;
            Object redisData = cacheService.getCachedData(redisKey);

            if (redisData != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> inventoryData = (Map<String, Object>) redisData;
                inventoryData.put("source", "redis");
                return inventoryData;
            }

            // Redis에 없으면 MySQL에서 조회 후 동기화
            Optional<Inventory> inventoryOpt = inventoryRepository.findById(productId);
            if (inventoryOpt.isPresent()) {
                Inventory inventory = inventoryOpt.get();

                Map<String, Object> inventoryData = new HashMap<>();
                inventoryData.put("product_id", productId);
                inventoryData.put("total_quantity", inventory.getTotalQuantity());
                inventoryData.put("available_quantity", inventory.getAvailableQuantity());
                inventoryData.put("reserved_quantity", inventory.getReservedQuantity());
                inventoryData.put("version", inventory.getVersion());
                inventoryData.put("source", "mysql");

                // Redis에 동기화
                syncInventoryToRedis(productId);

                return inventoryData;
            }

            return Map.of("error", "Product not found: " + productId);

        } catch (Exception e) {
            log.error("Error getting inventory status for product {}: {}", productId, e.getMessage(), e);
            return Map.of("error", "System error: " + e.getMessage());
        }
    }
}
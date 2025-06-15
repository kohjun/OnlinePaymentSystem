package com.example.payment.application.service;

import com.example.payment.domain.model.inventory.Inventory;
import com.example.payment.domain.InventoryTransaction;
import com.example.payment.domain.model.inventory.Reservation;
import com.example.payment.domain.repository.InventoryRepository;
import com.example.payment.domain.repository.InventoryTransactionRepository;
import com.example.payment.domain.repository.ReservationRepository;
import com.example.payment.infrastructure.persistance.redis.repository.CacheService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Redis와 MySQL 간 정합성을 보장하는 재고 관리 서비스
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class InventoryManagementService {

    private final InventoryRepository inventoryRepository;
    private final InventoryTransactionRepository transactionRepository;
    private final ReservationRepository reservationRepository;
    private final CacheService cacheService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    private static final String INVENTORY_TOPIC = "inventory-events";
    private static final int MAX_RETRY_ATTEMPTS = 3;

    /**
     * 재고 예약 (Redis + MySQL 동기화)
     */
    @Transactional
    public InventoryReservationResult reserveInventory(String productId, String reservationId,
                                                       int quantity, String orderId, String paymentId) {
        log.info("Reserving inventory: productId={}, reservationId={}, quantity={}",
                productId, reservationId, quantity);

        // 1. MySQL에서 재고 정보 조회 및 예약 시도
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                Optional<Inventory> inventoryOpt = inventoryRepository.findById(productId);
                if (inventoryOpt.isEmpty()) {
                    return InventoryReservationResult.failure("PRODUCT_NOT_FOUND",
                            "Product not found: " + productId);
                }

                Inventory inventory = inventoryOpt.get();

                // 가용 재고 확인
                if (inventory.getAvailableQuantity() < quantity) {
                    return InventoryReservationResult.failure("INSUFFICIENT_INVENTORY",
                            String.format("Available: %d, Requested: %d",
                                    inventory.getAvailableQuantity(), quantity));
                }

                // 재고 예약 (낙관적 락 사용)
                int updatedRows = inventoryRepository.reserveInventoryWithVersion(
                        productId, quantity, inventory.getVersion());

                if (updatedRows == 0) {
                    // 버전 충돌 - 재시도
                    log.warn("Optimistic locking failure on attempt {}/{} for product {}",
                            attempt, MAX_RETRY_ATTEMPTS, productId);
                    if (attempt == MAX_RETRY_ATTEMPTS) {
                        return InventoryReservationResult.failure("CONCURRENT_MODIFICATION",
                                "Failed to reserve after " + MAX_RETRY_ATTEMPTS + " attempts");
                    }
                    Thread.sleep(50 * attempt); // 백오프
                    continue;
                }

                // 2. 예약 정보 DB 저장
                Reservation reservation = Reservation.builder()
                        .id(reservationId)
                        .productId(productId)
                        .orderId(orderId)
                        .paymentId(paymentId)
                        .quantity(quantity)
                        .status(Reservation.ReservationStatus.PENDING)
                        .expiresAt(LocalDateTime.now().plusMinutes(15))
                        .build();

                reservationRepository.save(reservation);

                // 3. 재고 변경 이력 기록
                InventoryTransaction transaction = InventoryTransaction.builder()
                        .id(UUID.randomUUID().toString())
                        .productId(productId)
                        .transactionType(InventoryTransaction.TransactionType.RESERVE)
                        .quantityChange(-quantity)
                        .previousAvailable(inventory.getAvailableQuantity())
                        .newAvailable(inventory.getAvailableQuantity() - quantity)
                        .previousReserved(inventory.getReservedQuantity())
                        .newReserved(inventory.getReservedQuantity() + quantity)
                        .reservationId(reservationId)
                        .orderId(orderId)
                        .paymentId(paymentId)
                        .reason("Inventory reserved for order")
                        .createdBy("SYSTEM")
                        .build();

                transactionRepository.save(transaction);

                // 4. Redis 동기화
                syncInventoryToRedis(productId);

                // 5. 이벤트 발행
                publishInventoryEvent("INVENTORY_RESERVED", productId, reservationId, quantity);

                log.info("Successfully reserved inventory: productId={}, quantity={}, reservationId={}",
                        productId, quantity, reservationId);

                return InventoryReservationResult.success(reservationId,
                        inventory.getAvailableQuantity() - quantity);

            } catch (ObjectOptimisticLockingFailureException e) {
                log.warn("Optimistic locking failure on attempt {}/{} for product {}",
                        attempt, MAX_RETRY_ATTEMPTS, productId);
                if (attempt == MAX_RETRY_ATTEMPTS) {
                    return InventoryReservationResult.failure("CONCURRENT_MODIFICATION",
                            "Failed to reserve after " + MAX_RETRY_ATTEMPTS + " attempts due to concurrent modifications");
                }
                try {
                    Thread.sleep(50 * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return InventoryReservationResult.failure("INTERRUPTED", "Process was interrupted");
                }
            } catch (Exception e) {
                log.error("Error reserving inventory for product {}: {}", productId, e.getMessage(), e);
                return InventoryReservationResult.failure("SYSTEM_ERROR",
                        "System error during reservation: " + e.getMessage());
            }
        }

        return InventoryReservationResult.failure("MAX_RETRIES_EXCEEDED",
                "Maximum retry attempts exceeded");
    }

    /**
     * 예약 확정 (재고 차감)
     */
    @Transactional
    public boolean confirmReservation(String reservationId) {
        log.info("Confirming reservation: {}", reservationId);

        try {
            // 1. 예약 정보 조회
            Optional<Reservation> reservationOpt = reservationRepository.findById(reservationId);
            if (reservationOpt.isEmpty()) {
                log.warn("Reservation not found: {}", reservationId);
                return false;
            }

            Reservation reservation = reservationOpt.get();

            if (reservation.getStatus() != Reservation.ReservationStatus.PENDING) {
                log.warn("Reservation is not in PENDING status: {} - {}",
                        reservationId, reservation.getStatus());
                return false;
            }

            // 2. 재고 확정 처리
            for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
                try {
                    Optional<Inventory> inventoryOpt = inventoryRepository.findById(reservation.getProductId());
                    if (inventoryOpt.isEmpty()) {
                        log.error("Product not found for reservation: {}", reservationId);
                        return false;
                    }

                    Inventory inventory = inventoryOpt.get();

                    // 예약 확정 (재고 차감)
                    int updatedRows = inventoryRepository.confirmReservationWithVersion(
                            reservation.getProductId(), reservation.getQuantity(), inventory.getVersion());

                    if (updatedRows == 0) {
                        // 버전 충돌 - 재시도
                        if (attempt == MAX_RETRY_ATTEMPTS) {
                            log.error("Failed to confirm reservation after {} attempts: {}",
                                    MAX_RETRY_ATTEMPTS, reservationId);
                            return false;
                        }
                        Thread.sleep(50 * attempt);
                        continue;
                    }

                    // 3. 예약 상태 업데이트
                    reservation.setStatus(Reservation.ReservationStatus.CONFIRMED);
                    reservationRepository.save(reservation);

                    // 4. 이력 기록
                    InventoryTransaction transaction = InventoryTransaction.builder()
                            .id(UUID.randomUUID().toString())
                            .productId(reservation.getProductId())
                            .transactionType(InventoryTransaction.TransactionType.CONFIRM)
                            .quantityChange(-reservation.getQuantity())
                            .previousReserved(inventory.getReservedQuantity())
                            .newReserved(inventory.getReservedQuantity() - reservation.getQuantity())
                            .previousAvailable(inventory.getAvailableQuantity())
                            .newAvailable(inventory.getAvailableQuantity())
                            .reservationId(reservationId)
                            .orderId(reservation.getOrderId())
                            .paymentId(reservation.getPaymentId())
                            .reason("Reservation confirmed")
                            .createdBy("SYSTEM")
                            .build();

                    transactionRepository.save(transaction);

                    // 5. Redis 동기화
                    syncInventoryToRedis(reservation.getProductId());

                    // 6. 이벤트 발행
                    publishInventoryEvent("INVENTORY_CONFIRMED", reservation.getProductId(),
                            reservationId, reservation.getQuantity());

                    log.info("Successfully confirmed reservation: {}", reservationId);
                    return true;

                } catch (ObjectOptimisticLockingFailureException e) {
                    if (attempt == MAX_RETRY_ATTEMPTS) {
                        log.error("Failed to confirm reservation after optimistic locking failures: {}",
                                reservationId);
                        return false;
                    }
                    try {
                        Thread.sleep(50 * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }

        } catch (Exception e) {
            log.error("Error confirming reservation {}: {}", reservationId, e.getMessage(), e);
            return false;
        }

        return false;
    }

    /**
     * 예약 취소 (재고 복원)
     */
    @Transactional
    public boolean cancelReservation(String reservationId) {
        log.info("Cancelling reservation: {}", reservationId);

        try {
            // 1. 예약 정보 조회
            Optional<Reservation> reservationOpt = reservationRepository.findById(reservationId);
            if (reservationOpt.isEmpty()) {
                log.warn("Reservation not found: {}", reservationId);
                return false;
            }

            Reservation reservation = reservationOpt.get();

            if (reservation.getStatus() != Reservation.ReservationStatus.PENDING) {
                log.warn("Reservation is not in PENDING status: {} - {}",
                        reservationId, reservation.getStatus());
                return reservation.getStatus() == Reservation.ReservationStatus.CANCELLED;
            }

            // 2. 재고 복원 처리
            for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
                try {
                    Optional<Inventory> inventoryOpt = inventoryRepository.findById(reservation.getProductId());
                    if (inventoryOpt.isEmpty()) {
                        log.error("Product not found for reservation: {}", reservationId);
                        return false;
                    }

                    Inventory inventory = inventoryOpt.get();

                    // 예약 취소 (재고 복원)
                    int updatedRows = inventoryRepository.cancelReservationWithVersion(
                            reservation.getProductId(), reservation.getQuantity(), inventory.getVersion());

                    if (updatedRows == 0) {
                        // 버전 충돌 - 재시도
                        if (attempt == MAX_RETRY_ATTEMPTS) {
                            log.error("Failed to cancel reservation after {} attempts: {}",
                                    MAX_RETRY_ATTEMPTS, reservationId);
                            return false;
                        }
                        Thread.sleep(50 * attempt);
                        continue;
                    }

                    // 3. 예약 상태 업데이트
                    reservation.setStatus(Reservation.ReservationStatus.CANCELLED);
                    reservationRepository.save(reservation);

                    // 4. 이력 기록
                    InventoryTransaction transaction = InventoryTransaction.builder()
                            .id(UUID.randomUUID().toString())
                            .productId(reservation.getProductId())
                            .transactionType(InventoryTransaction.TransactionType.CANCEL)
                            .quantityChange(reservation.getQuantity())
                            .previousReserved(inventory.getReservedQuantity())
                            .newReserved(inventory.getReservedQuantity() - reservation.getQuantity())
                            .previousAvailable(inventory.getAvailableQuantity())
                            .newAvailable(inventory.getAvailableQuantity() + reservation.getQuantity())
                            .reservationId(reservationId)
                            .orderId(reservation.getOrderId())
                            .paymentId(reservation.getPaymentId())
                            .reason("Reservation cancelled")
                            .createdBy("SYSTEM")
                            .build();

                    transactionRepository.save(transaction);

                    // 5. Redis 동기화
                    syncInventoryToRedis(reservation.getProductId());

                    // 6. 이벤트 발행
                    publishInventoryEvent("INVENTORY_CANCELLED", reservation.getProductId(),
                            reservationId, reservation.getQuantity());

                    log.info("Successfully cancelled reservation: {}", reservationId);
                    return true;

                } catch (ObjectOptimisticLockingFailureException e) {
                    if (attempt == MAX_RETRY_ATTEMPTS) {
                        log.error("Failed to cancel reservation after optimistic locking failures: {}",
                                reservationId);
                        return false;
                    }
                    try {
                        Thread.sleep(50 * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }

        } catch (Exception e) {
            log.error("Error cancelling reservation {}: {}", reservationId, e.getMessage(), e);
            return false;
        }

        return false;
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

            // Redis에 재고 정보 저장
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
     * Redis와 MySQL 간 재고 정합성 검증
     */
    @Scheduled(fixedRate = 300000) // 5분마다 실행
    public void verifyInventoryConsistency() {
        log.info("Starting inventory consistency verification");

        try {
            List<Inventory> allInventory = inventoryRepository.findAll();
            int totalChecked = 0;
            int mismatches = 0;

            for (Inventory dbInventory : allInventory) {
                String productId = dbInventory.getProductId();
                String redisKey = "inventory:" + productId;

                // Redis에서 재고 정보 조회
                Object redisData = cacheService.getCachedData(redisKey);

                if (redisData == null) {
                    // Redis에 데이터가 없으면 동기화
                    syncInventoryToRedis(productId);
                    log.info("Synced missing inventory data to Redis: {}", productId);
                } else {
                    // 데이터 비교
                    Map<String, Object> redisInventory = (Map<String, Object>) redisData;

                    Integer redisAvailable = (Integer) redisInventory.get("available_quantity");
                    Integer redisReserved = (Integer) redisInventory.get("reserved_quantity");
                    Integer redisTotal = (Integer) redisInventory.get("total_quantity");

                    boolean mismatch = false;
                    if (!dbInventory.getAvailableQuantity().equals(redisAvailable) ||
                            !dbInventory.getReservedQuantity().equals(redisReserved) ||
                            !dbInventory.getTotalQuantity().equals(redisTotal)) {

                        mismatch = true;
                        mismatches++;

                        log.warn("Inventory mismatch detected for product {}: " +
                                        "DB(total={}, available={}, reserved={}) vs " +
                                        "Redis(total={}, available={}, reserved={})",
                                productId,
                                dbInventory.getTotalQuantity(), dbInventory.getAvailableQuantity(),
                                dbInventory.getReservedQuantity(),
                                redisTotal, redisAvailable, redisReserved);

                        // MySQL을 정답으로 간주하고 Redis 업데이트
                        syncInventoryToRedis(productId);

                        // 불일치 이벤트 발행
                        publishInventoryEvent("INVENTORY_MISMATCH_CORRECTED", productId, null, 0);
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
     * 만료된 예약 정리
     */
    @Scheduled(fixedRate = 60000) // 1분마다 실행
    public void cleanupExpiredReservations() {
        try {
            List<Reservation> expiredReservations = reservationRepository.findExpiredReservations(LocalDateTime.now());

            for (Reservation reservation : expiredReservations) {
                log.info("Processing expired reservation: {}", reservation.getId());

                // 예약 취소 처리
                boolean cancelled = cancelReservation(reservation.getId());
                if (cancelled) {
                    reservation.setStatus(Reservation.ReservationStatus.EXPIRED);
                    reservationRepository.save(reservation);

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
     * 재고 이벤트 발행
     */
    private void publishInventoryEvent(String eventType, String productId, String reservationId, int quantity) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("eventType", eventType);
            event.put("productId", productId);
            event.put("reservationId", reservationId);
            event.put("quantity", quantity);
            event.put("timestamp", System.currentTimeMillis());

            String eventJson = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(INVENTORY_TOPIC, productId, eventJson);

            log.debug("Published inventory event: {}", eventType);

        } catch (Exception e) {
            log.error("Error publishing inventory event: {}", e.getMessage(), e);
        }
    }

    /**
     * 재고 예약 결과 클래스
     */
    public static class InventoryReservationResult {
        private final boolean success;
        private final String reservationId;
        private final Integer remainingQuantity;
        private final String errorCode;
        private final String errorMessage;

        private InventoryReservationResult(boolean success, String reservationId,
                                           Integer remainingQuantity, String errorCode, String errorMessage) {
            this.success = success;
            this.reservationId = reservationId;
            this.remainingQuantity = remainingQuantity;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }

        public static InventoryReservationResult success(String reservationId, Integer remainingQuantity) {
            return new InventoryReservationResult(true, reservationId, remainingQuantity, null, null);
        }

        public static InventoryReservationResult failure(String errorCode, String errorMessage) {
            return new InventoryReservationResult(false, null, null, errorCode, errorMessage);
        }

        // Getters
        public boolean isSuccess() { return success; }
        public String getReservationId() { return reservationId; }
        public Integer getRemainingQuantity() { return remainingQuantity; }
        public String getErrorCode() { return errorCode; }
        public String getErrorMessage() { return errorMessage; }
    }
}
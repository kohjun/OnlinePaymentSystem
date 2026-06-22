package com.example.payment.presentation.controller;

import com.example.payment.domain.model.EventConfig;
import com.example.payment.domain.repository.ProductRepository;
import com.example.payment.domain.repository.InventoryRepository;
import com.example.payment.domain.repository.InventoryReservationRecordRepository;
import com.example.payment.domain.repository.OrderRecordRepository;
import com.example.payment.domain.repository.PaymentRecordRepository;
import com.example.payment.infrastructure.persistence.redis.repository.CacheService;
import com.example.payment.infrastructure.util.ResourceReservationService;
import com.example.payment.scheduler.InventoryReconciliationJob;
import com.example.payment.application.service.DistributionReadinessService;
import com.example.payment.application.service.SimulationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
@Slf4j
public class SystemController {

    private final CacheService cacheService;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryReservationRecordRepository inventoryReservationRecordRepository;
    private final OrderRecordRepository orderRecordRepository;
    private final PaymentRecordRepository paymentRecordRepository;
    private final ResourceReservationService resourceReservationService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final SimulationService simulationService;
    private final DistributionReadinessService distributionReadinessService;

    @Autowired(required = false)
    private InventoryReconciliationJob inventoryReconciliationJob;

    @Value("${app.queue.enabled:true}")
    private boolean queueEnabled;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> checkSystemHealth() {
        try {
            boolean redisHealthy = cacheService.isRedisConnected();
            return ResponseEntity.ok(Map.of(
                    "status", redisHealthy ? "UP" : "DOWN",
                    "components", Map.of(
                            "redis", Map.of(
                                    "status", redisHealthy ? "UP" : "DOWN",
                                    "connected", redisHealthy
                            )
                    ),
                    "timestamp", System.currentTimeMillis()
            ));
        } catch (Exception e) {
            log.error("Error checking system health", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "DOWN",
                    "error", e.getMessage(),
                    "timestamp", System.currentTimeMillis()
            ));
        }
    }

    @GetMapping("/health/redis")
    public ResponseEntity<Map<String, Object>> checkRedisHealth() {
        try {
            boolean connected = cacheService.isRedisConnected();
            return ResponseEntity.ok(Map.of(
                    "status", connected ? "UP" : "DOWN",
                    "connected", connected,
                    "timestamp", System.currentTimeMillis()
            ));
        } catch (Exception e) {
            log.error("Error checking Redis health", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "DOWN",
                    "connected", false,
                    "error", e.getMessage(),
                    "timestamp", System.currentTimeMillis()
            ));
        }
    }

    @GetMapping("/readiness")
    public ResponseEntity<DistributionReadinessService.ReadinessReport> checkDistributionReadiness() {
        DistributionReadinessService.ReadinessReport report = distributionReadinessService.evaluate();
        if (!report.releasable()) {
            return ResponseEntity.status(503).body(report);
        }
        return ResponseEntity.ok(report);
    }

    @GetMapping("/dashboard/status")
    public ResponseEntity<List<Map<String, Object>>> getDashboardStatus() {
        String activeId = simulationService.getActiveEventId();
        EventConfig activeEvent = simulationService.getEvent(activeId);
        
        if (activeEvent != null && (!"TICKETING".equals(activeEvent.getType()) || !activeEvent.getEventId().equals("CONCERT-IU"))) {
            List<Map<String, Object>> response = new ArrayList<>();
            Map<String, Object> item = new HashMap<>();
            String eventId = activeEvent.getEventId();
            
            item.put("productId", eventId);
            item.put("name", activeEvent.getTitle());
            item.put("price", activeEvent.getPrice());
            item.put("category", activeEvent.getType());
            item.put("description", activeEvent.getTitle());

            // 1. Postgres 재고 정보 조회
            inventoryRepository.findById(eventId).ifPresent(inv -> {
                item.put("dbTotal", inv.getTotalQuantity());
                item.put("dbAvailable", inv.getAvailableQuantity());
                item.put("dbReserved", inv.getReservedQuantity());
            });

            // 2. Redis 재고 정보 조회
            String resourceKey = "inventory:" + eventId;
            Map<String, Object> redisState = resourceReservationService.getResourceStatus(resourceKey);
            if (!redisState.isEmpty()) {
                item.put("redisTotal", toInt(redisState.get("total")));
                item.put("redisAvailable", toInt(redisState.get("available")));
                item.put("redisReserved", toInt(redisState.get("reserved")));
            } else {
                int total = activeEvent.getTotalInventory();
                item.put("redisTotal", total);
                item.put("redisAvailable", total);
                item.put("redisReserved", 0);
            }

            if (!item.containsKey("dbTotal")) {
                item.put("dbTotal", item.get("redisTotal"));
                item.put("dbAvailable", item.get("redisAvailable"));
                item.put("dbReserved", item.get("redisReserved"));
            }
            
            // 3. 불일치(Mismatch) 상태 계산
            boolean mismatch = false;
            Integer dbAvail = (Integer) item.get("dbAvailable");
            Integer redisAvail = (Integer) item.get("redisAvailable");
            Integer dbRes = (Integer) item.get("dbReserved");
            Integer redisRes = (Integer) item.get("redisReserved");
            if (dbAvail != null && redisAvail != null) {
                mismatch = !dbAvail.equals(redisAvail) || !Objects.equals(dbRes, redisRes);
            }
            item.put("mismatch", mismatch);

            // 4. 개별 좌석 락 정보 조회
            Map<String, String> lockedSeats = new HashMap<>();
            if ("TICKETING".equals(activeEvent.getType())) {
                // 커스텀 티켓팅은 prefix 'S'로 고정하여 locked_seat:S-* 매핑
                Set<String> lockedKeys = redisTemplate.keys("locked_seat:S-*");
                if (lockedKeys != null) {
                    for (String key : lockedKeys) {
                        String seatId = key.substring(key.indexOf(":") + 1);
                        Object owner = redisTemplate.opsForValue().get(key);
                        if (owner != null) {
                            lockedSeats.put(seatId, owner.toString());
                        }
                    }
                }
            } else {
                String seatId = "DRAW-NIKE-1";
                if ("AUCTION".equals(activeEvent.getType())) {
                    seatId = eventId;
                }
                String lockKey = "locked_seat:" + seatId;
                Object owner = redisTemplate.opsForValue().get(lockKey);
                if (owner != null) {
                    lockedSeats.put(seatId, owner.toString());
                }
            }
            item.put("lockedSeats", lockedSeats);
            
            response.add(item);
            return ResponseEntity.ok(response);
        }

        List<String> productIds = List.of("CONCERT-VIP", "CONCERT-R", "CONCERT-S");
        List<Map<String, Object>> response = new ArrayList<>();

        for (String productId : productIds) {
            Map<String, Object> item = new HashMap<>();
            item.put("productId", productId);

            // 1. 상품 정보 조회
            productRepository.findById(productId).ifPresent(p -> {
                item.put("name", p.getName());
                item.put("price", p.getPrice());
                item.put("category", p.getCategory());
                item.put("description", p.getDescription());
            });

            // 2. Postgres 재고 정보 조회
            inventoryRepository.findById(productId).ifPresent(inv -> {
                item.put("dbTotal", inv.getTotalQuantity());
                item.put("dbAvailable", inv.getAvailableQuantity());
                item.put("dbReserved", inv.getReservedQuantity());
            });

            // 3. Redis 재고 정보 조회
            String resourceKey = "inventory:" + productId;
            Map<String, Object> redisState = resourceReservationService.getResourceStatus(resourceKey);
            if (!redisState.isEmpty()) {
                item.put("redisTotal", toInt(redisState.get("total")));
                item.put("redisAvailable", toInt(redisState.get("available")));
                item.put("redisReserved", toInt(redisState.get("reserved")));
            } else {
                item.put("redisTotal", 0);
                item.put("redisAvailable", 0);
                item.put("redisReserved", 0);
            }

            // 4. 불일치(Mismatch) 상태 계산
            boolean mismatch = false;
            Integer dbAvail = (Integer) item.get("dbAvailable");
            Integer redisAvail = (Integer) item.get("redisAvailable");
            Integer dbRes = (Integer) item.get("dbReserved");
            Integer redisRes = (Integer) item.get("redisReserved");
            if (dbAvail != null && redisAvail != null) {
                mismatch = !dbAvail.equals(redisAvail) || !Objects.equals(dbRes, redisRes);
            }
            item.put("mismatch", mismatch);

            // 5. 실시간 개별 좌석 락 정보 조회 (예: locked_seat:V-*)
            String prefix = getSeatPrefix(productId);
            Set<String> lockedKeys = redisTemplate.keys("locked_seat:" + prefix + "-*");
            Map<String, String> lockedSeats = new HashMap<>();
            if (lockedKeys != null) {
                for (String key : lockedKeys) {
                    String seatId = key.substring(key.indexOf(":") + 1);
                    Object owner = redisTemplate.opsForValue().get(key);
                    if (owner != null) {
                        lockedSeats.put(seatId, owner.toString());
                    }
                }
            }
            item.put("lockedSeats", lockedSeats);

            response.add(item);
        }

        return ResponseEntity.ok(response);
    }

    @PostMapping("/dashboard/reset")
    @Transactional
    public ResponseEntity<Map<String, Object>> resetSystem() {
        try {
            log.info("System simulation reset requested.");
            // 예약, 주문, 결제 이력 모두 삭제
            paymentRecordRepository.deleteAll();
            orderRecordRepository.deleteAll();
            inventoryReservationRecordRepository.deleteAll();

            // Postgres 재고 초기화
            resetInventory("CONCERT-VIP", 24);
            resetInventory("CONCERT-R", 36);
            resetInventory("CONCERT-S", 48);
            resetInventory("DRAW-NIKE", 10);
            resetInventory("AUCTION-ROLEX", 1);

            // Redis 재고 초기화
            resetRedisInventory("CONCERT-VIP", 24);
            resetRedisInventory("CONCERT-R", 36);
            resetRedisInventory("CONCERT-S", 48);
            resetRedisInventory("DRAW-NIKE", 10);
            resetRedisInventory("AUCTION-ROLEX", 1);

            // 경매 이력 및 입찰 초기화
            redisTemplate.delete("auction_highest_bid:AUCTION-ROLEX");
            redisTemplate.delete("auction_highest_bidder:AUCTION-ROLEX");
            redisTemplate.delete("auction_history:AUCTION-ROLEX");
            Set<String> auctionLocks = redisTemplate.keys("auction_lock:*");
            if (auctionLocks != null && !auctionLocks.isEmpty()) redisTemplate.delete(auctionLocks);

            // 관련 캐시 삭제
            Set<String> keys = redisTemplate.keys("reservation:*");
            if (keys != null && !keys.isEmpty()) redisTemplate.delete(keys);

            Set<String> txKeys = redisTemplate.keys("tx_reservation:*");
            if (txKeys != null && !txKeys.isEmpty()) redisTemplate.delete(txKeys);

            Set<String> compKeys = redisTemplate.keys("complete_reservation:*");
            if (compKeys != null && !compKeys.isEmpty()) redisTemplate.delete(compKeys);

            Set<String> txCompKeys = redisTemplate.keys("tx_complete_reservation:*");
            if (txCompKeys != null && !txCompKeys.isEmpty()) redisTemplate.delete(txCompKeys);

            // 좌석 락 캐시 삭제
            Set<String> seatKeys = redisTemplate.keys("locked_seat:*");
            if (seatKeys != null && !seatKeys.isEmpty()) redisTemplate.delete(seatKeys);

            log.info("System simulation reset executed successfully.");
            return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "시스템 시뮬레이션 상태가 성공적으로 초기화되었습니다."));
        } catch (Exception e) {
            log.error("Failed to reset system simulation state", e);
            return ResponseEntity.internalServerError().body(Map.of("status", "FAILED", "message", "초기화 중 오류 발생: " + e.getMessage()));
        }
    }

    @PostMapping("/inventory/reconcile")
    public ResponseEntity<Map<String, Object>> triggerReconciliation() {
        log.info("Manual inventory reconciliation requested.");
        if (inventoryReconciliationJob != null) {
            inventoryReconciliationJob.reconcileInventoryCounters();
            return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "재고 정합성 복구 작업(Self-Healing)이 실행되었습니다."));
        } else {
            return ResponseEntity.badRequest().body(Map.of("status", "FAILED", "message", "정합성 복구 작업 빈이 활성화되지 않았습니다."));
        }
    }

    private void resetInventory(String productId, int quantity) {
        inventoryRepository.findById(productId).ifPresent(inv -> {
            inv.setTotalQuantity(quantity);
            inv.setAvailableQuantity(quantity);
            inv.setReservedQuantity(0);
            inv.setVersion(0L); // Optimistic Lock 버전을 0으로 리셋
            inventoryRepository.save(inv);
        });
    }

    private void resetRedisInventory(String productId, int quantity) {
        String resourceKey = "inventory:" + productId;
        resourceReservationService.initializeResource(resourceKey, quantity, quantity);
    }

    private int toInt(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @PostMapping("/seats/lock")
    public ResponseEntity<Map<String, Object>> lockSeat(
            @org.springframework.web.bind.annotation.RequestParam String seatId,
            @org.springframework.web.bind.annotation.RequestParam String customerId) {
        try {
            // 대기열 활성 상태(토큰) 확인 (시뮬레이터 가상 사용자는 대기열 체크를 우회)
            if (queueEnabled && !customerId.startsWith("SLA-CUST-")) {
                String activeKey = "active_user:" + customerId;
                Boolean isActive = redisTemplate.hasKey(activeKey);
                if (Boolean.FALSE.equals(isActive)) {
                    return ResponseEntity.status(403).body(Map.of(
                            "status", "QUEUE_EXPIRED",
                            "message", "대기열 세션이 존재하지 않거나 만료되었습니다. 다시 대기열에 진입해 주세요."
                    ));
                }
            }

            String key = "locked_seat:" + seatId;
            Object existingOwner = redisTemplate.opsForValue().get(key);
            
            if (existingOwner != null) {
                if ("SOLD".equals(existingOwner.toString())) {
                    return ResponseEntity.status(409).body(Map.of(
                            "status", "CONFLICT",
                            "message", "이미 예매가 완료된 좌석입니다."
                    ));
                }
                if (!existingOwner.toString().equals(customerId)) {
                    return ResponseEntity.status(409).body(Map.of(
                            "status", "CONFLICT",
                            "message", "이미 다른 사용자가 선점 중인 좌석입니다."
                    ));
                }
            } else {
                // Redis 락이 유실(Eviction 또는 재시작)되었는지 DB 크로스체크 (하이브리드 검증)
                String productId = getProductIdFromSeatId(seatId);
                if (productId != null) {
                    boolean alreadySoldInDb = inventoryReservationRecordRepository.existsBySeatIdAndStatus(seatId, "CONFIRMED");
                    if (alreadySoldInDb) {
                        // Redis SOLD 락 복구 (Write-through)
                        redisTemplate.opsForValue().set(key, "SOLD", 30 * 24 * 3600, java.util.concurrent.TimeUnit.SECONDS);
                        return ResponseEntity.status(409).body(Map.of(
                                "status", "CONFLICT",
                                "message", "이미 예매가 완료된 좌석입니다. (DB 확인됨)"
                        ));
                    }
                }
            }
            
            // 5분 동안 선점 유지
            redisTemplate.opsForValue().set(key, customerId, 300, java.util.concurrent.TimeUnit.SECONDS);
            log.info("Seat locked successfully in Redis: seatId={}, customerId={}", seatId, customerId);
            
            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "message", "좌석 선점 성공"
            ));
        } catch (Exception e) {
            log.error("Failed to lock seat: seatId={}", seatId, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "FAILED",
                    "message", "좌석 선점 중 시스템 오류 발생: " + e.getMessage()
            ));
        }
    }

    @PostMapping("/seats/unlock")
    public ResponseEntity<Map<String, Object>> unlockSeat(
            @org.springframework.web.bind.annotation.RequestParam String seatId,
            @org.springframework.web.bind.annotation.RequestParam String customerId) {
        try {
            String key = "locked_seat:" + seatId;
            Object owner = redisTemplate.opsForValue().get(key);
            
            if (owner != null && owner.toString().equals(customerId)) {
                redisTemplate.delete(key);
                log.info("Seat unlocked successfully in Redis: seatId={}, customerId={}", seatId, customerId);
            }
            
            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "message", "좌석 선점 해제 성공"
            ));
        } catch (Exception e) {
            log.error("Failed to unlock seat: seatId={}", seatId, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "FAILED",
                    "message", "좌석 선점 해제 중 시스템 오류 발생: " + e.getMessage()
            ));
        }
    }

    @PostMapping("/seats/confirm")
    public ResponseEntity<Map<String, Object>> confirmSeat(
            @org.springframework.web.bind.annotation.RequestParam String seatId) {
        try {
            String key = "locked_seat:" + seatId;
            // 결제 완료된 자리는 CUST-ID 대신 "SOLD"로 영구 락 설정 (30일 동안 유지)
            redisTemplate.opsForValue().set(key, "SOLD", 30 * 24 * 3600, java.util.concurrent.TimeUnit.SECONDS);
            log.info("Seat confirmed as SOLD in Redis: seatId={}", seatId);
            
            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "message", "좌석 예매 완료 확정 성공"
            ));
        } catch (Exception e) {
            log.error("Failed to confirm seat: seatId={}", seatId, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "FAILED",
                    "message", "좌석 확정 중 시스템 오류 발생: " + e.getMessage()
            ));
        }
    }

    @GetMapping("/customer/{customerId}/bookings")
    public ResponseEntity<List<Map<String, Object>>> getCustomerBookings(@org.springframework.web.bind.annotation.PathVariable String customerId) {
        try {
            List<com.example.payment.domain.entity.InventoryReservationRecord> reservations = 
                    inventoryReservationRecordRepository.findByCustomerIdOrderByCreatedAtDesc(
                            customerId, 
                            org.springframework.data.domain.PageRequest.of(0, 50)
                    );
            
            List<Map<String, Object>> bookings = new ArrayList<>();
            for (com.example.payment.domain.entity.InventoryReservationRecord res : reservations) {
                if ("CONFIRMED".equals(res.getStatus())) {
                    Map<String, Object> booking = new HashMap<>();
                    booking.put("reservationId", res.getReservationId());
                    booking.put("productId", res.getProductId());
                    booking.put("seatId", res.getSeatId());
                    booking.put("status", res.getStatus());
                    booking.put("createdAt", res.getCreatedAt());

                    if (res.getOrderId() != null) {
                        orderRecordRepository.findById(res.getOrderId()).ifPresent(order -> {
                            booking.put("orderId", order.getOrderId());
                            booking.put("amount", order.getAmount());
                            booking.put("currency", order.getCurrency());
                        });
                    }

                    if (res.getPaymentId() != null) {
                        paymentRecordRepository.findById(res.getPaymentId()).ifPresent(payment -> {
                            booking.put("paymentId", payment.getPaymentId());
                            booking.put("transactionId", payment.getTransactionId());
                            booking.put("approvalNumber", payment.getApprovalNumber());
                            booking.put("processedAt", payment.getProcessedAt());
                        });
                    }
                    bookings.add(booking);
                }
            }
            return ResponseEntity.ok(bookings);
        } catch (Exception e) {
            log.error("Failed to fetch customer bookings for {}", customerId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    private String getProductIdFromSeatId(String seatId) {
        if (seatId.startsWith("V-")) return "CONCERT-VIP";
        if (seatId.startsWith("R-")) return "CONCERT-R";
        if (seatId.startsWith("S-")) return "CONCERT-S";
        if (seatId.startsWith("DRAW-")) {
            int lastDash = seatId.lastIndexOf("-");
            return lastDash > 0 ? seatId.substring(0, lastDash) : seatId;
        }
        if (seatId.startsWith("AUCTION-")) return seatId;
        if (seatId.contains("-")) {
            int lastDash = seatId.lastIndexOf("-");
            return lastDash > 0 ? seatId.substring(0, lastDash) : seatId;
        }
        return null;
    }

    private String getSeatPrefix(String productId) {
        if ("CONCERT-VIP".equals(productId)) return "V";
        if ("CONCERT-R".equals(productId)) return "R";
        if ("CONCERT-S".equals(productId)) return "S";
        return "";
    }
}

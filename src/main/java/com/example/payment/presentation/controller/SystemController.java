package com.example.payment.presentation.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.ResponseEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.example.payment.infrastructure.persistance.redis.repository.CacheService;
import com.example.payment.application.service.InventoryManagementService;
import com.example.payment.infrastructure.buffer.WriteBufferService;
import com.example.payment.infrastructure.buffer.BufferStatus;  // 올바른 import

/**
 * 시스템 상태 확인 및 관리 컨트롤러
 */
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
@Slf4j
public class SystemController {

    private final CacheService cacheService;
    private final InventoryManagementService inventoryService;
    private final WriteBufferService writeBufferService;

    /**
     * Write Buffer 상태 조회 - 수정된 버전
     */
    @GetMapping("/buffer/status")
    public ResponseEntity<BufferStatus> getBufferStatus() {
        try {
            BufferStatus status = writeBufferService.getBufferStatus();
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            log.error("Error getting buffer status", e);
            // 오류 시 기본 상태 반환
            BufferStatus errorStatus = BufferStatus.builder()
                    .primaryBufferSize(-1)
                    .retryBufferSize(-1)
                    .totalEnqueued(0)
                    .totalProcessed(0)
                    .totalFailed(0)
                    .totalRejected(0)
                    .build();
            return ResponseEntity.internalServerError().body(errorStatus);
        }
    }

    /**
     * 버퍼 상태 상세 정보 (JSON 형태)
     */
    @GetMapping("/buffer/details")
    public ResponseEntity<java.util.Map<String, Object>> getBufferDetails() {
        try {
            BufferStatus status = writeBufferService.getBufferStatus();

            java.util.Map<String, Object> details = new java.util.HashMap<>();
            details.put("buffers", java.util.Map.of(
                    "primary", status.getPrimaryBufferSize(),
                    "retry", status.getRetryBufferSize(),
                    "total", status.getTotalBufferSize()
            ));

            details.put("metrics", java.util.Map.of(
                    "enqueued", status.getTotalEnqueued(),
                    "processed", status.getTotalProcessed(),
                    "failed", status.getTotalFailed(),
                    "rejected", status.getTotalRejected(),
                    "pending", status.getPendingCommands()
            ));

            details.put("rates", java.util.Map.of(
                    "success", String.format("%.2f%%", status.getSuccessRate()),
                    "failure", String.format("%.2f%%", status.getFailureRate()),
                    "rejection", String.format("%.2f%%", status.getRejectionRate())
            ));

            details.put("health", status.getHealthStatus());
            details.put("timestamp", System.currentTimeMillis());

            return ResponseEntity.ok(details);

        } catch (Exception e) {
            log.error("Error getting buffer details", e);
            return ResponseEntity.internalServerError()
                    .body(java.util.Map.of("error", "Failed to get buffer details: " + e.getMessage()));
        }
    }

    /**
     * 버퍼 강제 플러시 (관리자용)
     */
    @GetMapping("/buffer/flush")
    public ResponseEntity<String> forceFlushBuffers() {
        try {
            writeBufferService.forceFlushAllBuffers();
            return ResponseEntity.ok("All buffers flushed successfully");
        } catch (Exception e) {
            log.error("Error flushing buffers", e);
            return ResponseEntity.internalServerError()
                    .body("Failed to flush buffers: " + e.getMessage());
        }
    }

    /**
     * 특정 타입 명령 플러시 (관리자용)
     */
    @GetMapping("/buffer/flush/{commandType}")
    public ResponseEntity<String> flushCommandType(@PathVariable String commandType) {
        try {
            writeBufferService.flushCommandType(commandType);
            return ResponseEntity.ok("Commands of type '" + commandType + "' flushed successfully");
        } catch (Exception e) {
            log.error("Error flushing command type: {}", commandType, e);
            return ResponseEntity.internalServerError()
                    .body("Failed to flush command type: " + e.getMessage());
        }
    }

    /**
     * 전체 시스템 상태 확인
     */
    @GetMapping("/health")
    public ResponseEntity<java.util.Map<String, Object>> checkSystemHealth() {
        try {
            // Redis 연결 상태 확인
            boolean redisHealthy = cacheService.isRedisConnected();

            // 버퍼 상태 확인
            BufferStatus bufferStatus = writeBufferService.getBufferStatus();
            String bufferHealth = bufferStatus.getHealthStatus();

            // 전체 시스템 상태 판단
            String overallStatus = "HEALTHY";
            if (!redisHealthy || "CRITICAL".equals(bufferHealth)) {
                overallStatus = "CRITICAL";
            } else if ("WARNING".equals(bufferHealth)) {
                overallStatus = "WARNING";
            }

            java.util.Map<String, Object> health = java.util.Map.of(
                    "status", overallStatus,
                    "redis", redisHealthy ? "OK" : "DISCONNECTED",
                    "buffer", bufferHealth,
                    "timestamp", System.currentTimeMillis()
            );

            return ResponseEntity.ok(health);

        } catch (Exception e) {
            log.error("Error checking system health", e);
            return ResponseEntity.internalServerError()
                    .body(java.util.Map.of(
                            "status", "ERROR",
                            "error", e.getMessage(),
                            "timestamp", System.currentTimeMillis()
                    ));
        }
    }

    /**
     * Redis 연결 상태 확인
     */
    @GetMapping("/health/redis")
    public ResponseEntity<java.util.Map<String, Object>> checkRedisHealth() {
        try {
            boolean connected = cacheService.isRedisConnected();

            java.util.Map<String, Object> redisHealth = java.util.Map.of(
                    "status", connected ? "OK" : "DISCONNECTED",
                    "connected", connected,
                    "timestamp", System.currentTimeMillis()
            );

            return ResponseEntity.ok(redisHealth);
        } catch (Exception e) {
            log.error("Error checking Redis health", e);
            return ResponseEntity.internalServerError()
                    .body(java.util.Map.of(
                            "status", "ERROR",
                            "connected", false,
                            "error", e.getMessage(),
                            "timestamp", System.currentTimeMillis()
                    ));
        }
    }

    /**
     * 특정 상품의 재고 상태 조회 (관리자용)
     */
    @GetMapping("/inventory/{productId}")
    public ResponseEntity<java.util.Map<String, Object>> getInventoryStatus(@PathVariable String productId) {
        try {
            java.util.Map<String, Object> inventoryStatus = inventoryService.getInventoryStatus(productId);
            inventoryStatus.put("timestamp", System.currentTimeMillis());
            return ResponseEntity.ok(inventoryStatus);
        } catch (Exception e) {
            log.error("Error getting inventory status for product: {}", productId, e);
            return ResponseEntity.internalServerError()
                    .body(java.util.Map.of(
                            "error", "Failed to get inventory status: " + e.getMessage(),
                            "productId", productId,
                            "timestamp", System.currentTimeMillis()
                    ));
        }
    }

    /**
     * 캐시 통계 조회 (관리자용)
     */
    @GetMapping("/cache/stats")
    public ResponseEntity<java.util.Map<String, Object>> getCacheStats() {
        try {
            // 기본 캐시 통계 정보
            java.util.Map<String, Object> stats = java.util.Map.of(
                    "redis_connected", cacheService.isRedisConnected(),
                    "message", "Cache statistics - 구현 예정 (MonitoringService 확장 필요)",
                    "timestamp", System.currentTimeMillis()
            );

            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            log.error("Error getting cache stats", e);
            return ResponseEntity.internalServerError()
                    .body(java.util.Map.of(
                            "error", "Failed to get cache stats: " + e.getMessage(),
                            "timestamp", System.currentTimeMillis()
                    ));
        }
    }

    /**
     * 시스템 메트릭 조회 (관리자용)
     */
    @GetMapping("/metrics")
    public ResponseEntity<java.util.Map<String, Object>> getSystemMetrics() {
        try {
            BufferStatus bufferStatus = writeBufferService.getBufferStatus();

            java.util.Map<String, Object> metrics = java.util.Map.of(
                    "buffer_metrics", java.util.Map.of(
                            "total_enqueued", bufferStatus.getTotalEnqueued(),
                            "total_processed", bufferStatus.getTotalProcessed(),
                            "total_failed", bufferStatus.getTotalFailed(),
                            "success_rate", bufferStatus.getSuccessRate(),
                            "failure_rate", bufferStatus.getFailureRate()
                    ),
                    "redis_connected", cacheService.isRedisConnected(),
                    "message", "Extended metrics - Actuator 사용 권장",
                    "timestamp", System.currentTimeMillis()
            );

            return ResponseEntity.ok(metrics);
        } catch (Exception e) {
            log.error("Error getting system metrics", e);
            return ResponseEntity.internalServerError()
                    .body(java.util.Map.of(
                            "error", "Failed to get system metrics: " + e.getMessage(),
                            "timestamp", System.currentTimeMillis()
                    ));
        }
    }
}
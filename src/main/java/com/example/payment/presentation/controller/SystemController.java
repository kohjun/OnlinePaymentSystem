package com.example.payment.presentation.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.ResponseEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.example.payment.infrastructure.persistence.redis.repository.CacheService;
import com.example.payment.application.service.InventoryManagementService;

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


    /**
     * 전체 시스템 상태 확인
     */
    @GetMapping("/health")
    public ResponseEntity<java.util.Map<String, Object>> checkSystemHealth() {
        try {
            // Redis 연결 상태 확인
            boolean redisHealthy = cacheService.isRedisConnected();

            // 전체 시스템 상태 판단
            String overallStatus = "HEALTHY";
            if (!redisHealthy) {
                overallStatus = "CRITICAL";
            }

            java.util.Map<String, Object> health = java.util.Map.of(
                    "status", overallStatus,
                    "redis", redisHealthy ? "OK" : "DISCONNECTED",
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
}
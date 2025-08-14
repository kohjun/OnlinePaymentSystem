package com.example.payment.presentation.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.example.payment.infrastructure.persistance.redis.repository.CacheService;
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
    public String checkSystemHealth() {
        try {
            // Redis 연결 상태 확인
            boolean redisHealthy = cacheService.isRedisConnected();

            return String.format("System Status: %s | Redis: %s",
                    "OK",
                    redisHealthy ? "OK" : "DISCONNECTED");

        } catch (Exception e) {
            log.error("Error checking system health", e);
            return "System Status: ERROR - " + e.getMessage();
        }
    }

    /**
     * Redis 연결 상태 확인
     */
    @GetMapping("/health/redis")
    public String checkRedisHealth() {
        try {
            boolean connected = cacheService.isRedisConnected();
            return connected ? "Redis: OK" : "Redis: DISCONNECTED";
        } catch (Exception e) {
            log.error("Error checking Redis health", e);
            return "Redis: ERROR - " + e.getMessage();
        }
    }

    /**
     * 특정 상품의 재고 상태 조회 (관리자용)
     */
    @GetMapping("/inventory/{productId}")
    public java.util.Map<String, Object> getInventoryStatus(@org.springframework.web.bind.annotation.PathVariable String productId) {
        try {
            return inventoryService.getInventoryStatus(productId);
        } catch (Exception e) {
            log.error("Error getting inventory status for product: {}", productId, e);
            return java.util.Map.of("error", "Failed to get inventory status: " + e.getMessage());
        }
    }

    /**
     * 캐시 통계 조회 (관리자용)
     */
    @GetMapping("/cache/stats")
    public String getCacheStats() {
        return "Cache statistics - 구현 예정";
    }

    /**
     * 시스템 메트릭 조회 (관리자용)
     */
    @GetMapping("/metrics")
    public String getSystemMetrics() {
        return "System metrics - 구현 예정 (Actuator 사용 권장)";
    }
}
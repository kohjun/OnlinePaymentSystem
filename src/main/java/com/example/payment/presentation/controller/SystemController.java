package com.example.payment.presentation.controller;

import com.example.payment.application.service.DistributionReadinessService;
import com.example.payment.infrastructure.persistence.redis.repository.CacheService;
import com.example.payment.infrastructure.security.AuthorizationGuard;
import com.example.payment.infrastructure.security.SecurityAuditService;
import com.example.payment.scheduler.InventoryReconciliationJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
@Slf4j
public class SystemController {

    private final CacheService cacheService;
    private final DistributionReadinessService distributionReadinessService;
    private final AuthorizationGuard authorizationGuard;
    private final SecurityAuditService securityAuditService;

    @Autowired(required = false)
    private InventoryReconciliationJob inventoryReconciliationJob;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> checkSystemHealth() {
        try {
            boolean redisHealthy = cacheService.isRedisConnected();
            return ResponseEntity.ok(Map.of(
                    "status", redisHealthy ? "UP" : "DOWN",
                    "components", Map.of(
                            "redis", Map.of("status", redisHealthy ? "UP" : "DOWN", "connected", redisHealthy)
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
        return report.releasable()
                ? ResponseEntity.ok(report)
                : ResponseEntity.status(503).body(report);
    }

    @GetMapping("/inventory/reconcile")
    public ResponseEntity<Map<String, Object>> inspectReconciliation() {
        authorizationGuard.requireAdmin();
        if (inventoryReconciliationJob == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "UNAVAILABLE",
                    "mismatchCount", 0,
                    "mismatches", List.of(),
                    "message", "재고 정합성 작업이 활성화되지 않았습니다."
            ));
        }
        List<InventoryReconciliationJob.InventoryMismatch> mismatches = inventoryReconciliationJob.findMismatches();
        return ResponseEntity.ok(Map.of(
                "status", mismatches.isEmpty() ? "CONSISTENT" : "MISMATCH_DETECTED",
                "mismatchCount", mismatches.size(),
                "mismatches", mismatches,
                "message", mismatches.isEmpty()
                        ? "Postgres와 Redis 재고가 일치합니다."
                        : "복구 전에 불일치 항목을 확인해 주세요."
        ));
    }

    @PostMapping("/inventory/reconcile")
    public ResponseEntity<Map<String, Object>> triggerReconciliation() {
        authorizationGuard.requireAdmin();
        securityAuditService.recordGranted("INVENTORY_RECONCILE_REQUESTED", "INVENTORY", "all");
        if (inventoryReconciliationJob == null) {
            securityAuditService.record(
                    "INVENTORY_RECONCILE_UNAVAILABLE",
                    "INVENTORY",
                    "all",
                    "FAILED",
                    "Inventory reconciliation job is not available."
            );
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "FAILED",
                    "message", "정합성 복구 작업이 활성화되지 않았습니다."
            ));
        }
        inventoryReconciliationJob.reconcileInventoryCounters();
        securityAuditService.recordGranted("INVENTORY_RECONCILE_SUCCEEDED", "INVENTORY", "all");
        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "message", "재고 정합성 복구 작업이 실행되었습니다."
        ));
    }
}

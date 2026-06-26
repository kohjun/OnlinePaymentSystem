package com.example.payment.presentation.controller;

import com.example.payment.infrastructure.security.AuthorizationGuard;
import com.example.payment.infrastructure.security.SecurityAuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/queue")
@RequiredArgsConstructor
@Slf4j
public class QueueController {

    private final RedisTemplate<String, Object> redisTemplate;
    private final AuthorizationGuard authorizationGuard;
    private final SecurityAuditService securityAuditService;

    @Value("${app.queue.enabled:true}")
    private boolean queueEnabled;

    @PostMapping("/join")
    public ResponseEntity<Map<String, Object>> joinQueue(@RequestParam String customerId) {
        authorizationGuard.requireCustomerAccess(customerId);
        if (!queueEnabled) {
            return activeResponse("Queue is disabled; request may proceed immediately.");
        }

        String activeKey = "active_user:" + customerId;
        Boolean isActive = redisTemplate.hasKey(activeKey);
        
        if (Boolean.TRUE.equals(isActive)) {
            return activeResponse("Active queue token already exists.");
        }

        // Add to sorted set queue
        redisTemplate.opsForZSet().add("ticket_queue", customerId, System.currentTimeMillis());
        Long rank = redisTemplate.opsForZSet().rank("ticket_queue", customerId);
        long currentRank = (rank != null) ? rank + 1 : 1;
        long estimatedTime = calculateEstimatedTime(currentRank);

        log.info("Customer joined standby queue: customerId={}, rank={}, estimatedTime={}s", customerId, currentRank, estimatedTime);

        return ResponseEntity.ok(Map.of(
                "status", "WAITING",
                "rank", currentRank,
                "waitingTime", estimatedTime
        ));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getQueueStatus(@RequestParam String customerId) {
        authorizationGuard.requireCustomerAccess(customerId);
        if (!queueEnabled) {
            return activeResponse("Queue is disabled; request may proceed immediately.");
        }

        String activeKey = "active_user:" + customerId;
        Boolean isActive = redisTemplate.hasKey(activeKey);
        
        if (Boolean.TRUE.equals(isActive)) {
            return activeResponse("Active queue token already exists.");
        }

        Long rank = redisTemplate.opsForZSet().rank("ticket_queue", customerId);
        if (rank != null) {
            long currentRank = rank + 1;
            long estimatedTime = calculateEstimatedTime(currentRank);
            return ResponseEntity.ok(Map.of(
                    "status", "WAITING",
                    "rank", currentRank,
                    "waitingTime", estimatedTime
            ));
        }

        return ResponseEntity.ok(Map.of(
                "status", "NONE",
                "rank", -1,
                "waitingTime", 0
        ));
    }

    @PostMapping("/clear")
    public ResponseEntity<Map<String, Object>> clearQueue(@RequestParam String customerId) {
        authorizationGuard.requireAdmin();
        securityAuditService.recordGranted("QUEUE_TOKEN_CLEARED", "QUEUE", customerId);
        redisTemplate.opsForZSet().remove("ticket_queue", customerId);
        redisTemplate.delete("active_user:" + customerId);
        log.info("Cleared standby queue token for customer: {}", customerId);
        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "message", "대기열 세션이 초기화되었습니다."
        ));
    }

    private long calculateEstimatedTime(long rank) {
        // Assume we promote 50 users per second.
        // Wait time = rank / 50. Minimum 1 second.
        return Math.max(1, rank / 50);
    }

    private ResponseEntity<Map<String, Object>> activeResponse(String message) {
        return ResponseEntity.ok(Map.of(
                "status", "ACTIVE",
                "rank", 0,
                "waitingTime", 0,
                "queueEnabled", queueEnabled,
                "message", message
        ));
    }
}

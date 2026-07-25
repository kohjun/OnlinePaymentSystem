package com.example.payment.presentation.controller;

import com.example.payment.application.service.StandbyQueueService;
import com.example.payment.infrastructure.security.AuthorizationGuard;
import com.example.payment.infrastructure.security.SecurityAuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/queue")
@RequiredArgsConstructor
@Slf4j
public class QueueController {

    private final StandbyQueueService standbyQueueService;
    private final AuthorizationGuard authorizationGuard;
    private final SecurityAuditService securityAuditService;

    @PostMapping("/join")
    public ResponseEntity<Map<String, Object>> joinQueue(
            @RequestParam(required = false) String customerId) {
        customerId = authorizationGuard.currentCustomerId();
        StandbyQueueService.QueueStatus status = standbyQueueService.join(customerId);
        log.info("Customer queue join resolved: customerId={}, status={}, rank={}", customerId, status.status(), status.rank());
        return ResponseEntity.ok(toResponse(status));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getQueueStatus(
            @RequestParam(required = false) String customerId) {
        customerId = authorizationGuard.currentCustomerId();
        return ResponseEntity.ok(toResponse(standbyQueueService.status(customerId)));
    }

    @PostMapping("/leave")
    public ResponseEntity<Map<String, Object>> leaveQueue(
            @RequestParam(required = false) String customerId) {
        customerId = authorizationGuard.currentCustomerId();
        standbyQueueService.clear(customerId);
        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "message", "대기열에서 나갔습니다."
        ));
    }

    @PostMapping("/clear")
    public ResponseEntity<Map<String, Object>> clearQueue(@RequestParam String customerId) {
        authorizationGuard.requireAdmin();
        securityAuditService.recordGranted("QUEUE_TOKEN_CLEARED", "QUEUE", customerId);
        standbyQueueService.clear(customerId);
        log.info("Cleared standby queue token for customer: {}", customerId);
        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "message", "대기열 세션이 초기화되었습니다."
        ));
    }

    private Map<String, Object> toResponse(StandbyQueueService.QueueStatus status) {
        return Map.of(
                "status", status.status(),
                "rank", status.rank(),
                "waitingTime", status.waitingTime(),
                "queueEnabled", status.queueEnabled(),
                "message", status.message()
        );
    }
}

package com.example.payment.presentation.controller;

import com.example.payment.application.service.AdminOperationsActionService;
import com.example.payment.application.service.AdminOperationsAuditService;
import com.example.payment.application.service.AdminOperationsQueueService;
import com.example.payment.infrastructure.security.AuthorizationGuard;
import com.example.payment.infrastructure.security.EverySalePrincipal;
import com.example.payment.presentation.dto.request.AdminOperationActionRequest;
import com.example.payment.presentation.dto.response.AdminOperationActionResponse;
import com.example.payment.presentation.dto.response.AdminOperationAuditResponse;
import com.example.payment.presentation.dto.response.AdminOperationsQueueResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/operations")
@RequiredArgsConstructor
public class AdminOperationsController {

    private final AdminOperationsQueueService queueService;
    private final AdminOperationsActionService actionService;
    private final AdminOperationsAuditService auditService;
    private final AuthorizationGuard authorizationGuard;

    @GetMapping("/queues")
    public ResponseEntity<AdminOperationsQueueResponse> getQueues(
            @RequestParam(name = "limit", required = false) Integer limit) {
        authorizationGuard.requireAdmin();
        return ResponseEntity.ok(queueService.getQueues(limit));
    }

    @GetMapping("/audit")
    public ResponseEntity<AdminOperationAuditResponse> getAuditEvents(
            @RequestParam(name = "queue", required = false) String queue,
            @RequestParam(name = "itemId", required = false) String itemId,
            @RequestParam(name = "limit", required = false) Integer limit) {
        authorizationGuard.requireAdmin();
        return ResponseEntity.ok(auditService.getAuditEvents(queue, itemId, limit));
    }

    @PostMapping("/queues/{queue}/items/{itemId}/actions/{action}")
    public ResponseEntity<?> executeAction(@PathVariable String queue,
                                           @PathVariable String itemId,
                                           @PathVariable String action,
                                           @Valid @RequestBody(required = false) AdminOperationActionRequest request) {
        try {
            authorizationGuard.requireAdmin();
            EverySalePrincipal principal = authorizationGuard.currentPrincipal();
            AdminOperationActionResponse response = actionService.execute(queue, itemId, action, request, principal);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "status", "FAILED",
                    "message", e.getMessage()
            ));
        }
    }
}

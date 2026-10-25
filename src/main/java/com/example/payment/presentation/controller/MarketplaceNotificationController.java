package com.example.payment.presentation.controller;

import com.example.payment.application.service.MarketplaceNotificationService;
import com.example.payment.infrastructure.security.AuthorizationGuard;
import com.example.payment.presentation.dto.response.MarketplaceNotificationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/c2c/notifications")
@RequiredArgsConstructor
public class MarketplaceNotificationController {

    private final MarketplaceNotificationService notificationService;
    private final AuthorizationGuard authorizationGuard;

    @GetMapping
    public ResponseEntity<MarketplaceNotificationResponse.Inbox> getInbox(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {
        return ResponseEntity.ok(notificationService.getInbox(authorizationGuard.currentPrincipal(), page, size));
    }

    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<MarketplaceNotificationResponse> markRead(@PathVariable String notificationId) {
        return ResponseEntity.ok(notificationService.markRead(authorizationGuard.currentPrincipal(), notificationId));
    }

    @PatchMapping("/read-all")
    public ResponseEntity<Map<String, Integer>> markAllRead() {
        return ResponseEntity.ok(Map.of("updatedCount", notificationService.markAllRead(authorizationGuard.currentPrincipal())));
    }
}

package com.example.payment.presentation.dto.response;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.List;

@Value
@Builder
@lombok.extern.jackson.Jacksonized
public class MarketplaceNotificationResponse {
    String notificationId;
    String notificationType;
    String title;
    String message;
    String actionPath;
    String aggregateType;
    String aggregateId;
    boolean read;
    LocalDateTime readAt;
    LocalDateTime createdAt;

    @Value
    @Builder
    @lombok.extern.jackson.Jacksonized
    public static class Inbox {
        List<MarketplaceNotificationResponse> notifications;
        long unreadCount;
    }
}

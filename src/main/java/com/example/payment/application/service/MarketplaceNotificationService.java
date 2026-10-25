package com.example.payment.application.service;

import com.example.payment.domain.model.marketplace.MarketplaceNotification;
import com.example.payment.domain.repository.MarketplaceNotificationRepository;
import com.example.payment.infrastructure.security.EverySalePrincipal;
import com.example.payment.presentation.dto.response.MarketplaceNotificationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MarketplaceNotificationService {

    private final MarketplaceNotificationRepository notificationRepository;
    private final MarketplaceNotificationWriter notificationWriter;

    @Transactional
    public void notifyCustomer(String customerId,
                               String type,
                               String title,
                               String message,
                               String aggregateType,
                               String aggregateId,
                               String idempotencyKey) {
        save(null, customerId, null, type, title, message, aggregateType, aggregateId, idempotencyKey);
    }

    @Transactional
    public void notifySeller(String sellerId,
                             String type,
                             String title,
                             String message,
                             String aggregateType,
                             String aggregateId,
                             String idempotencyKey) {
        save(null, null, sellerId, type, title, message, aggregateType, aggregateId, idempotencyKey);
    }

    @Transactional(readOnly = true)
    public MarketplaceNotificationResponse.Inbox getInbox(EverySalePrincipal principal, int page, int size) {
        Recipient recipient = recipient(principal);
        var notifications = notificationRepository.findForRecipient(
                        recipient.userId(), recipient.customerId(), recipient.sellerId(),
                        PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100))))
                .stream()
                .map(this::toResponse)
                .toList();
        long unreadCount = notificationRepository.countUnreadForRecipient(
                recipient.userId(), recipient.customerId(), recipient.sellerId());
        return MarketplaceNotificationResponse.Inbox.builder()
                .notifications(notifications)
                .unreadCount(unreadCount)
                .build();
    }

    @Transactional
    public MarketplaceNotificationResponse markRead(EverySalePrincipal principal, String notificationId) {
        Recipient recipient = recipient(principal);
        MarketplaceNotification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found."));
        if (!belongsTo(notification, recipient)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found.");
        }
        if (notification.getReadAt() == null) {
            notification.setReadAt(LocalDateTime.now());
            notification = notificationRepository.save(notification);
        }
        return toResponse(notification);
    }

    @Transactional
    public int markAllRead(EverySalePrincipal principal) {
        Recipient recipient = recipient(principal);
        return notificationRepository.markAllRead(
                recipient.userId(), recipient.customerId(), recipient.sellerId(), LocalDateTime.now());
    }

    private void save(String userId,
                      String customerId,
                      String sellerId,
                      String type,
                      String title,
                      String message,
                      String aggregateType,
                      String aggregateId,
                      String idempotencyKey) {
        requireText(idempotencyKey, "Notification idempotency key is required.");
        if (notificationRepository.existsByIdempotencyKey(idempotencyKey)) {
            return;
        }
        notificationWriter.insertIfAbsent(
                "NTF-" + UUID.randomUUID().toString().replace("-", ""),
                trimToNull(userId),
                trimToNull(customerId),
                trimToNull(sellerId),
                requireText(type, "Notification type is required."),
                requireText(title, "Notification title is required."),
                requireText(message, "Notification message is required."),
                actionPath(aggregateType, aggregateId),
                trimToNull(aggregateType),
                trimToNull(aggregateId),
                idempotencyKey.trim(),
                LocalDateTime.now()
        );
    }

    private Recipient recipient(EverySalePrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }
        String userId = trimToNull(principal.userId());
        String customerId = trimToNull(principal.customerId());
        String sellerId = trimToNull(principal.sellerId());
        if (userId == null && customerId == null && sellerId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Notification identity is required.");
        }
        return new Recipient(userId, customerId, sellerId);
    }

    private boolean belongsTo(MarketplaceNotification notification, Recipient recipient) {
        return matches(notification.getRecipientUserId(), recipient.userId())
                || matches(notification.getRecipientCustomerId(), recipient.customerId())
                || matches(notification.getRecipientSellerId(), recipient.sellerId());
    }

    private boolean matches(String expected, String actual) {
        return expected != null && expected.equals(actual);
    }

    private MarketplaceNotificationResponse toResponse(MarketplaceNotification notification) {
        return MarketplaceNotificationResponse.builder()
                .notificationId(notification.getNotificationId())
                .notificationType(notification.getNotificationType())
                .title(notification.getTitle())
                .message(notification.getMessage())
                .actionPath(notification.getActionPath())
                .aggregateType(notification.getAggregateType())
                .aggregateId(notification.getAggregateId())
                .read(notification.getReadAt() != null)
                .readAt(notification.getReadAt())
                .createdAt(notification.getCreatedAt())
                .build();
    }

    private String actionPath(String aggregateType, String aggregateId) {
        if ("MARKETPLACE_ORDER".equals(aggregateType) && trimToNull(aggregateId) != null) {
            return "/app/?view=orders&orderId=" + aggregateId;
        }
        return "/app/";
    }

    private String requireText(String value, String message) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record Recipient(String userId, String customerId, String sellerId) {
    }
}

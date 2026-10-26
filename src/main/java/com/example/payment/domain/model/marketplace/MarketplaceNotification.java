package com.example.payment.domain.model.marketplace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "marketplace_notifications")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketplaceNotification {

    @Id
    @Column(name = "notification_id", length = 64)
    private String notificationId;

    @Column(name = "recipient_user_id")
    private String recipientUserId;

    @Column(name = "recipient_customer_id")
    private String recipientCustomerId;

    @Column(name = "recipient_seller_id")
    private String recipientSellerId;

    @Column(name = "notification_type", nullable = false, length = 60)
    private String notificationType;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, length = 1000)
    private String message;

    @Column(name = "action_path", length = 1000)
    private String actionPath;

    @Column(name = "aggregate_type", length = 60)
    private String aggregateType;

    @Column(name = "aggregate_id")
    private String aggregateId;

    @Column(name = "idempotency_key", nullable = false, length = 255, unique = true)
    private String idempotencyKey;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}

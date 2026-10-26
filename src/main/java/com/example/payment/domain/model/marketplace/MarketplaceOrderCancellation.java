package com.example.payment.domain.model.marketplace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "marketplace_order_cancellations")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketplaceOrderCancellation {
    @Id
    @Column(name = "cancellation_id")
    private String cancellationId;

    @Column(name = "marketplace_order_id", nullable = false, unique = true)
    private String marketplaceOrderId;

    @Column(name = "payment_id", nullable = false)
    private String paymentId;

    @Column(name = "refund_idempotency_key", nullable = false, unique = true)
    private String refundIdempotencyKey;

    @Column(name = "requested_by_type", nullable = false)
    private String requestedByType;

    @Column(name = "requested_by_id", nullable = false)
    private String requestedById;

    @Column(length = 1000)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderCancellationStatus status;

    @Column(name = "refund_id")
    private String refundId;

    @Column(name = "failure_reason", length = 2000)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

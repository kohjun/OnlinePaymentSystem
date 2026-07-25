package com.example.payment.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "inventory_sync_issues")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventorySyncIssue {

    @Id
    @Column(name = "issue_id", length = 80)
    private String issueId;

    @Column(name = "reservation_id", nullable = false, length = 100)
    private String reservationId;

    @Column(name = "product_id", nullable = false, length = 100)
    private String productId;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "issue_type", nullable = false, length = 40)
    private String issueType;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = createdAt == null ? now : createdAt;
        updatedAt = now;
        attemptCount = attemptCount == null ? 0 : attemptCount;
        status = status == null ? "PENDING" : status;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

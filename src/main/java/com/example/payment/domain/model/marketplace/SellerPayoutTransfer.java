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

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "seller_payout_transfers")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SellerPayoutTransfer {

    @Id
    @Column(name = "transfer_id", nullable = false, length = 64)
    private String transferId;

    @Column(name = "payout_id", nullable = false, length = 64)
    private String payoutId;

    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PayoutTransferStatus status;

    @Column(nullable = false, length = 100)
    private String provider;

    @Column(name = "provider_transfer_id", length = 255)
    private String providerTransferId;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
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
        if (attemptCount == null) {
            attemptCount = 0;
        }
        if (status == null) {
            status = PayoutTransferStatus.CREATED;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

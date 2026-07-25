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
@Table(name = "seller_payout_accounts")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SellerPayoutAccount {

    @Id
    @Column(name = "payout_account_id")
    private String payoutAccountId;

    @Column(name = "seller_id", nullable = false)
    private String sellerId;

    @Column(name = "account_ref", nullable = false, length = 500)
    private String accountRef;

    @Column(name = "bank_code", nullable = false, length = 50)
    private String bankCode;

    @Column(name = "bank_name", nullable = false, length = 100)
    private String bankName;

    @Column(name = "account_holder_name", nullable = false, length = 100)
    private String accountHolderName;

    @Column(name = "account_last4", nullable = false, length = 4)
    private String accountLast4;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SellerPayoutAccountStatus status;

    @Column(name = "review_note", length = 1000)
    private String reviewNote;

    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;

    @Column(name = "reviewed_by", length = 100)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (submittedAt == null) {
            submittedAt = now;
        }
        if (status == null) {
            status = SellerPayoutAccountStatus.PENDING_REVIEW;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

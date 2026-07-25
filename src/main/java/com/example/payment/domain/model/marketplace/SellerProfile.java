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
@Table(name = "sellers")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SellerProfile {

    @Id
    @Column(name = "seller_id")
    private String sellerId;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "owner_user_id")
    private String ownerUserId;

    @Column(name = "owner_customer_id")
    private String ownerCustomerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SellerStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false)
    private SellerVerificationStatus verificationStatus;

    @Column(name = "verification_evidence_ref", length = 500)
    private String verificationEvidenceRef;

    @Column(name = "verification_note", length = 1000)
    private String verificationNote;

    @Column(name = "verification_submitted_at")
    private LocalDateTime verificationSubmittedAt;

    @Column(name = "verification_reviewed_by", length = 100)
    private String verificationReviewedBy;

    @Column(name = "verification_reviewed_at")
    private LocalDateTime verificationReviewedAt;

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
        updatedAt = now;
        if (status == null) {
            status = SellerStatus.PENDING;
        }
        if (verificationStatus == null) {
            verificationStatus = SellerVerificationStatus.UNVERIFIED;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

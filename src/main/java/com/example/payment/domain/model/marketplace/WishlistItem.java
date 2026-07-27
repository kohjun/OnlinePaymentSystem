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
@Table(name = "wishlist_items")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WishlistItem {

    @Id
    @Column(name = "wishlist_item_id", length = 64)
    private String wishlistItemId;

    /** 담은 고객. 클라이언트가 보낸 값이 아니라 인증 신원에서 채운다. */
    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(name = "sale_event_id", nullable = false)
    private String saleEventId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}

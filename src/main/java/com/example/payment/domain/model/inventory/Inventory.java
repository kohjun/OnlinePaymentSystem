package com.example.payment.domain.model.inventory;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 재고 엔티티
 */
@Entity
@Table(name = "inventory")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Inventory {
    @Id
    @Column(name = "product_id")
    private String productId;

    @Column(name = "total_quantity", nullable = false)
    private Integer totalQuantity;

    @Column(name = "available_quantity", nullable = false)
    private Integer availableQuantity;

    @Column(name = "reserved_quantity", nullable = false)
    private Integer reservedQuantity;

    @Version
    private Long version;

    @Column(name = "last_updated_at")
    private LocalDateTime lastUpdatedAt;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    @PreUpdate
    protected void onUpdate() {
        lastUpdatedAt = LocalDateTime.now();
    }

    /**
     * 재고 예약
     */
    public boolean reserve(int quantity) {
        if (availableQuantity >= quantity) {
            availableQuantity -= quantity;
            reservedQuantity += quantity;
            return true;
        }
        return false;
    }

    /**
     * 예약 확정 (재고 차감)
     */
    public boolean confirm(int quantity) {
        if (reservedQuantity >= quantity) {
            reservedQuantity -= quantity;
            totalQuantity -= quantity;
            return true;
        }
        return false;
    }

    /**
     * 예약 취소 (재고 복원)
     */
    public boolean cancel(int quantity) {
        if (reservedQuantity >= quantity) {
            reservedQuantity -= quantity;
            availableQuantity += quantity;
            return true;
        }
        return false;
    }
}

package com.example.payment.domain.entity;

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
@Table(name = "inventory_movements")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryMovement {
    @Id
    @Column(name = "movement_id")
    private String movementId;

    @Column(name = "operation_key", nullable = false, unique = true)
    private String operationKey;

    @Column(name = "marketplace_order_id", nullable = false)
    private String marketplaceOrderId;

    @Column(name = "reservation_id")
    private String reservationId;

    @Column(name = "product_id", nullable = false)
    private String productId;

    @Column(nullable = false)
    private Integer quantity;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String status;

    @Column(name = "failure_reason", length = 2000)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}

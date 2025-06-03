package com.example.payment.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
/**
 * 재고 변경 이력 엔티티
 */
@Entity
@Table(name = "inventory_transactions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryTransaction {
    @Id
    private String id;

    @Column(name = "product_id", nullable = false)
    private String productId;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false)
    private TransactionType transactionType;

    @Column(name = "quantity_change", nullable = false)
    private Integer quantityChange;

    @Column(name = "previous_available", nullable = false)
    private Integer previousAvailable;

    @Column(name = "new_available", nullable = false)
    private Integer newAvailable;

    @Column(name = "previous_reserved", nullable = false)
    private Integer previousReserved;

    @Column(name = "new_reserved", nullable = false)
    private Integer newReserved;

    @Column(name = "reservation_id")
    private String reservationId;

    @Column(name = "order_id")
    private String orderId;

    @Column(name = "payment_id")
    private String paymentId;

    private String reason;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private String createdBy;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public enum TransactionType {
        RESERVE, CONFIRM, CANCEL, RESTOCK, ADJUSTMENT
    }
}
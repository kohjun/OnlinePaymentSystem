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
@Table(name = "marketplace_orders")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketplaceOrder {

    @Id
    @Column(name = "marketplace_order_id")
    private String marketplaceOrderId;

    @Column(name = "sale_event_id", nullable = false)
    private String saleEventId;

    @Column(name = "listing_id", nullable = false)
    private String listingId;

    @Column(name = "seller_id", nullable = false)
    private String sellerId;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sale_type", nullable = false)
    private SaleType saleType;

    @Enumerated(EnumType.STRING)
    @Column(name = "checkout_type", nullable = false)
    private MarketplaceCheckoutType checkoutType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MarketplaceOrderStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "fulfillment_status", nullable = false)
    private FulfillmentStatus fulfillmentStatus;

    @Column(name = "product_id", nullable = false)
    private String productId;

    @Column(nullable = false)
    private Integer quantity;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Column(name = "reservation_id")
    private String reservationId;

    @Column(name = "order_id")
    private String orderId;

    @Column(name = "payment_id")
    private String paymentId;

    @Column(name = "workflow_id")
    private String workflowId;

    @Column(name = "source_id")
    private String sourceId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "fulfilled_at")
    private LocalDateTime fulfilledAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (status == null) {
            status = MarketplaceOrderStatus.PENDING;
        }
        if (fulfillmentStatus == null) {
            fulfillmentStatus = FulfillmentStatus.NOT_READY;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

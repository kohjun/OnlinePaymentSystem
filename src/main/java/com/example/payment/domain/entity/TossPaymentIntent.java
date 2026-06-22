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

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "toss_payment_intents")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TossPaymentIntent {

    @Id
    @Column(name = "intent_id")
    private String intentId;

    @Column(name = "order_id", nullable = false)
    private String orderId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(name = "product_id", nullable = false)
    private String productId;

    @Column(nullable = false)
    private Integer quantity;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Column(name = "payment_method", nullable = false)
    private String paymentMethod;

    @Column(name = "order_name", nullable = false)
    private String orderName;

    @Column(name = "customer_key", nullable = false)
    private String customerKey;

    @Column(name = "merchant_id")
    private String merchantId;

    @Column(name = "client_id")
    private String clientId;

    @Column(name = "seat_id")
    private String seatId;

    @Column(nullable = false)
    private String status;

    @Column(name = "payment_key")
    private String paymentKey;

    @Column(name = "workflow_id")
    private String workflowId;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "success_url", columnDefinition = "TEXT")
    private String successUrl;

    @Column(name = "fail_url", columnDefinition = "TEXT")
    private String failUrl;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

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
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

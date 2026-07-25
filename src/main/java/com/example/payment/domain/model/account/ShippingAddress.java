package com.example.payment.domain.model.account;

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
@Table(name = "shipping_addresses")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShippingAddress {

    @Id
    @Column(name = "address_id")
    private String addressId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(name = "label", length = 100)
    private String label;

    @Column(name = "recipient_name", nullable = false, length = 100)
    private String recipientName;

    @Column(name = "contact_phone", nullable = false, length = 50)
    private String contactPhone;

    @Column(name = "postal_code", length = 30)
    private String postalCode;

    @Column(name = "address_line1", nullable = false, length = 500)
    private String addressLine1;

    @Column(name = "address_line2", length = 500)
    private String addressLine2;

    @Column(name = "delivery_memo", length = 500)
    private String deliveryMemo;

    @Column(name = "default_address", nullable = false)
    private Boolean defaultAddress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ShippingAddressStatus status;

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
        if (defaultAddress == null) {
            defaultAddress = false;
        }
        if (status == null) {
            status = ShippingAddressStatus.ACTIVE;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

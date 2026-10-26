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
import java.time.ZoneOffset;

@Entity
@Table(name = "sale_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SaleEvent {

    @Id
    @Column(name = "sale_event_id")
    private String saleEventId;

    @Column(name = "listing_id", nullable = false)
    private String listingId;

    @Column(name = "seller_id", nullable = false)
    private String sellerId;

    @Column(name = "product_id", nullable = false)
    private String productId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sale_type", nullable = false)
    private SaleType saleType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SaleEventStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "activation_mode", nullable = false)
    private SaleEventActivationMode activationMode;

    @Column(name = "activation_failure_reason", length = 1000)
    private String activationFailureReason;

    @Column(name = "active_schedule_slot", unique = true)
    private String activeScheduleSlot;

    @Column(name = "starts_at", nullable = false)
    private LocalDateTime startsAt;

    @Column(name = "ends_at")
    private LocalDateTime endsAt;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal price;

    @Column(name = "stock_quantity", nullable = false)
    private Integer stockQuantity;

    @Column(name = "max_quantity_per_customer", nullable = false)
    @Builder.Default
    private Integer maxQuantityPerCustomer = 1;

    @Column(name = "min_bid_increment", precision = 19, scale = 2)
    private BigDecimal minBidIncrement;

    @Column(name = "reserve_price", precision = 19, scale = 2)
    private BigDecimal reservePrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "close_reason")
    private AuctionCloseReason closeReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (status == null) {
            status = SaleEventStatus.SCHEDULED;
        }
        if (activationMode == null) {
            activationMode = status == SaleEventStatus.LIVE
                    ? SaleEventActivationMode.IMMEDIATE
                    : SaleEventActivationMode.SCHEDULED;
        }
        synchronizeActiveScheduleSlot();
        if (maxQuantityPerCustomer == null) {
            maxQuantityPerCustomer = saleType == SaleType.FIXED_PRICE ? stockQuantity : 1;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(ZoneOffset.UTC);
        synchronizeActiveScheduleSlot();
    }

    private void synchronizeActiveScheduleSlot() {
        activeScheduleSlot = status == SaleEventStatus.SCHEDULED || status == SaleEventStatus.LIVE
                ? listingId
                : null;
    }
}

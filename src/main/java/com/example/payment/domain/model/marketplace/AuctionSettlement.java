package com.example.payment.domain.model.marketplace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "auction_settlements")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuctionSettlement {
    @Id
    @Column(name = "settlement_id")
    private String settlementId;

    @Column(name = "sale_event_id", nullable = false)
    private String saleEventId;

    @Column(name = "winning_bid_id", nullable = false)
    private String winningBidId;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(name = "seller_id", nullable = false)
    private String sellerId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuctionSettlementStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;
}

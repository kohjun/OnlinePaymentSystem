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
@Table(name = "auction_bids")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuctionBid {
    @Id
    @Column(name = "bid_id")
    private String bidId;

    @Column(name = "sale_event_id", nullable = false)
    private String saleEventId;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(name = "bid_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal bidAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuctionBidStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}

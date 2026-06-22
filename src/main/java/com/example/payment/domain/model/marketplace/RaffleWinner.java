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

import java.time.LocalDateTime;

@Entity
@Table(name = "raffle_winners")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RaffleWinner {

    @Id
    @Column(name = "winner_id")
    private String winnerId;

    @Column(name = "sale_event_id", nullable = false)
    private String saleEventId;

    @Column(name = "entry_id", nullable = false)
    private String entryId;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "checkout_status", nullable = false)
    private RaffleCheckoutStatus checkoutStatus;

    @Column(name = "draw_seed")
    private String drawSeed;

    @Column(name = "drawn_by")
    private String drawnBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "checkout_completed_at")
    private LocalDateTime checkoutCompletedAt;
}

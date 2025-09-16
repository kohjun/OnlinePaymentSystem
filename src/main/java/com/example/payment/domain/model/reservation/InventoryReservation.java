package com.example.payment.domain.model.reservation;

import com.example.payment.domain.model.common.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryReservation extends BaseEntity {
    private String reservationId;
    private String productId;
    private String customerId;
    private Integer quantity;
    private ReservationStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }

    public long getRemainingSeconds() {
        if (isExpired()) return 0;
        return Duration.between(LocalDateTime.now(), expiresAt).getSeconds();
    }

    public boolean canBeConfirmed() {
        return status == ReservationStatus.RESERVED && !isExpired();
    }

    public boolean canBeCancelled() {
        return status == ReservationStatus.RESERVED || status == ReservationStatus.EXPIRED;
    }
}
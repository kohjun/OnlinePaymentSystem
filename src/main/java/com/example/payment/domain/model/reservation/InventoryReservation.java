package com.example.payment.domain.model.reservation;

import com.example.payment.domain.model.common.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode; // [수정] 임포트
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true) // [수정] 추가
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryReservation extends BaseEntity {
    // ... (기존 필드 동일) ...
    private String reservationId;
    private String productId;
    private String customerId;
    private Integer quantity;
    private ReservationStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;

    // ... (기존 메서드 동일) ...
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
        // [이전 수정 사항 유지]
        return status == ReservationStatus.RESERVED ||
                status == ReservationStatus.EXPIRED ||
                status == ReservationStatus.CONFIRMED;
    }
}
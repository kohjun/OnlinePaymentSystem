package com.example.payment.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ReservationState {
    private String reservationId;
    private String paymentId;
    private String productId;
    private String customerId;
    private Integer quantity;
    private BigDecimal amount;
    private String currency;
    private String status; // RESERVED, PROCESSING, CONFIRMED, EXPIRED, CANCELLED
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;

    /**
     * 예약 만료 여부 확인
     */
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }

    /**
     * 남은 시간(초) 계산
     */
    public long getRemainingSeconds() {
        if (isExpired()) return 0;
        return java.time.Duration.between(LocalDateTime.now(), expiresAt).getSeconds();
    }

    /**
     * 예약 유효성 확인
     */
    public boolean isValidForPayment() {
        return "RESERVED".equals(status) && !isExpired();
    }
}
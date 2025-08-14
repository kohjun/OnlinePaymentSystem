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
public class CompletedOrder {
    private String orderId;
    private String customerId;
    private String productId;
    private Integer quantity;
    private BigDecimal amount;
    private String currency;
    private String paymentId;
    private String reservationId;
    private String status; // CONFIRMED, PAID, SHIPPED, DELIVERED, CANCELLED
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 취소 가능 여부 확인
     */
    public boolean isCancellable() {
        return "CONFIRMED".equals(status) || "PAID".equals(status);
    }

    /**
     * 상태 업데이트
     */
    public void updateStatus(String newStatus) {
        this.status = newStatus;
        this.updatedAt = LocalDateTime.now();
    }
}
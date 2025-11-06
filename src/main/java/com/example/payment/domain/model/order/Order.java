package com.example.payment.domain.model.order;

import com.example.payment.domain.model.common.BaseEntity;
import com.example.payment.domain.model.common.Money;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode; // [수정] 임포트
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true) // [수정] 추가
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order extends BaseEntity {
    // ... (기존 필드 동일) ...
    private String orderId;
    private String customerId;
    private String productId;
    private Integer quantity;
    private Money amount;
    private String reservationId;
    private String paymentId;
    private String currency;
    private OrderStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ... (기존 메서드 동일) ...
    public boolean canBeCancelled() {
        // [이전 수정 사항 유지]
        return status == OrderStatus.CREATED ||
                status == OrderStatus.CONFIRMED ||
                status == OrderStatus.PAID;
    }

    public boolean isPaid() {
        return status == OrderStatus.PAID;
    }

    public void markAsPaid(String paymentId) {
        this.paymentId = paymentId;
        this.status = OrderStatus.PAID;
        this.updatedAt = LocalDateTime.now();
    }
}
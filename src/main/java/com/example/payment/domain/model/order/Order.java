package com.example.payment.domain.model.order;

import com.example.payment.domain.model.common.BaseEntity;
import com.example.payment.domain.model.common.Money;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order extends BaseEntity {
    private String orderId;
    private String customerId;
    private String productId;
    private Integer quantity;
    private Money amount;  // Money 값 객체 사용
    private String reservationId;
    private String paymentId;
    private String currency;
    private OrderStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public boolean canBeCancelled() {
        return status == OrderStatus.CREATED || status == OrderStatus.CONFIRMED;
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
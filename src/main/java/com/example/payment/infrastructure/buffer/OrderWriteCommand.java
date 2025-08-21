/**
 * 주문 쓰기 명령
 */
package com.example.payment.infrastructure.buffer;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OrderWriteCommand implements WriteCommand {
    private String commandId;
    private String orderId;
    private String customerId;
    private String productId;
    private Integer quantity;
    private BigDecimal amount;
    private String currency;
    private String paymentId;
    private String reservationId;
    private String status;
    private LocalDateTime createdAt;
    private int retryCount;

    public OrderWriteCommand(String orderId, String customerId, String productId, Integer quantity,
                             BigDecimal amount, String currency, String paymentId, String reservationId) {
        this.commandId = UUID.randomUUID().toString();
        this.orderId = orderId;
        this.customerId = customerId;
        this.productId = productId;
        this.quantity = quantity;
        this.amount = amount;
        this.currency = currency;
        this.paymentId = paymentId;
        this.reservationId = reservationId;
        this.status = "CONFIRMED";
        this.createdAt = LocalDateTime.now();
        this.retryCount = 0;
    }

    @Override
    public String getType() {
        return "ORDER_WRITE";
    }

    @Override
    public Object getPayload() {
        return this;
    }

    @Override
    public void incrementRetryCount() {
        this.retryCount++;
    }

    @Override
    public boolean canRetry() {
        return retryCount < 3;
    }
}

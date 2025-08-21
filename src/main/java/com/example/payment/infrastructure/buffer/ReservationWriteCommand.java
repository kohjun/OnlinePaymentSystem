/**
 * 예약 쓰기 명령
 */
package com.example.payment.infrastructure.buffer;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ReservationWriteCommand implements WriteCommand {
    private String commandId;
    private String reservationId;
    private String productId;
    private String customerId;
    private Integer quantity;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private int retryCount;

    public ReservationWriteCommand(String reservationId, String productId, String customerId,
                                   Integer quantity, String status, LocalDateTime expiresAt) {
        this.commandId = UUID.randomUUID().toString();
        this.reservationId = reservationId;
        this.productId = productId;
        this.customerId = customerId;
        this.quantity = quantity;
        this.status = status;
        this.expiresAt = expiresAt;
        this.createdAt = LocalDateTime.now();
        this.retryCount = 0;
    }

    @Override
    public String getType() {
        return "RESERVATION_WRITE";
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
        return retryCount < 3; // 최대 3번 재시도
    }
}
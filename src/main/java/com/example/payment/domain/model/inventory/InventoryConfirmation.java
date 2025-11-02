package com.example.payment.domain.model.inventory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryConfirmation {

    private String reservationId;
    private String orderId;
    private String paymentId;
    private boolean success;
    private String message;
    private LocalDateTime confirmedAt;

    /**
     * ✅ 필수 메서드
     */
    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    /**
     * 성공 응답 생성
     */
    public static InventoryConfirmation success(
            String reservationId,
            String orderId,
            String paymentId,
            String message,
            LocalDateTime confirmedAt) {

        return InventoryConfirmation.builder()
                .reservationId(reservationId)
                .orderId(orderId)
                .paymentId(paymentId)
                .success(true)
                .message(message)
                .confirmedAt(confirmedAt)
                .build();
    }

    /**
     * 실패 응답 생성
     */
    public static InventoryConfirmation failure(
            String reservationId,
            String orderId,
            String paymentId,
            String message) {

        return InventoryConfirmation.builder()
                .reservationId(reservationId)
                .orderId(orderId)
                .paymentId(paymentId)
                .success(false)
                .message(message)
                .confirmedAt(null)
                .build();
    }
}
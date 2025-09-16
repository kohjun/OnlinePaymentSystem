package com.example.payment.domain.model.inventory;

import com.example.payment.domain.model.common.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryConfirmation extends BaseEntity {
    private String reservationId;
    private String orderId;
    private String paymentId;
    private String productId;
    private Integer quantity;
    private boolean confirmed;
    private String reason;
    private LocalDateTime confirmedAt;

    public static InventoryConfirmation success(String reservationId, String orderId, String paymentId,
                                                String productId, Integer quantity) {
        return InventoryConfirmation.builder()
                .reservationId(reservationId)
                .orderId(orderId)
                .paymentId(paymentId)
                .productId(productId)
                .quantity(quantity)
                .confirmed(true)
                .reason("정상 확정")
                .confirmedAt(LocalDateTime.now())
                .build();
    }

    public static InventoryConfirmation failure(String reservationId, String orderId, String paymentId,
                                                String reason) {
        return InventoryConfirmation.builder()
                .reservationId(reservationId)
                .orderId(orderId)
                .paymentId(paymentId)
                .confirmed(false)
                .reason(reason)
                .confirmedAt(LocalDateTime.now())
                .build();
    }
}

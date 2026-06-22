package com.example.payment.application.temporal;

import com.example.payment.presentation.dto.request.CompleteReservationRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReservationWorkflowCommand {
    private String workflowId;
    private String idempotencyKey;
    private String correlationId;
    private String reservationId;
    private String orderId;
    private String paymentId;
    private String productId;
    private String customerId;
    private Integer quantity;
    private BigDecimal amount;
    private BigDecimal unitPrice;
    private String priceSource;
    private LocalDateTime priceCalculatedAt;
    private String currency;
    private String paymentMethod;
    private String tossPaymentKey;
    private String tossOrderId;
    private String tossIntentId;
    private String clientId;
    private String seatId;

    public static ReservationWorkflowCommand from(String workflowId,
                                                  String reservationId,
                                                  String orderId,
                                                  String paymentId,
                                                  CompleteReservationRequest request) {
        return ReservationWorkflowCommand.builder()
                .workflowId(workflowId)
                .idempotencyKey(request.getIdempotencyKey())
                .correlationId(request.getCorrelationId())
                .reservationId(reservationId)
                .orderId(orderId)
                .paymentId(paymentId)
                .productId(request.getProductId())
                .customerId(request.getCustomerId())
                .quantity(request.getQuantity())
                .amount(request.getPaymentInfo().getAmount())
                .unitPrice(unitPrice(request))
                .priceSource("SERVER_PRODUCT_PRICE")
                .priceCalculatedAt(LocalDateTime.now())
                .currency(request.getPaymentInfo().getCurrency())
                .paymentMethod(request.getPaymentInfo().getPaymentMethod())
                .tossPaymentKey(request.getPaymentInfo().getTossPaymentKey())
                .tossOrderId(request.getPaymentInfo().getTossOrderId())
                .tossIntentId(request.getPaymentInfo().getTossIntentId())
                .clientId(request.getClientId())
                .seatId(request.getSeatId())
                .build();
    }

    private static BigDecimal unitPrice(CompleteReservationRequest request) {
        if (request.getPaymentInfo().getAmount() == null || request.getQuantity() == null || request.getQuantity() <= 0) {
            return null;
        }
        return request.getPaymentInfo().getAmount()
                .divide(BigDecimal.valueOf(request.getQuantity()), 2, RoundingMode.HALF_UP);
    }
}

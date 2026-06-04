package com.example.payment.application.temporal;

import com.example.payment.presentation.dto.request.CompleteReservationRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

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
    private String currency;
    private String paymentMethod;
    private String clientId;

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
                .currency(request.getPaymentInfo().getCurrency())
                .paymentMethod(request.getPaymentInfo().getPaymentMethod())
                .clientId(request.getClientId())
                .build();
    }
}

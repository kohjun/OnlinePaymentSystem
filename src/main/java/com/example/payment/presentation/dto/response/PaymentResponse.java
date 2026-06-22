package com.example.payment.presentation.dto.response;

import com.example.payment.presentation.dto.common.BaseResponse;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class PaymentResponse extends BaseResponse {

    private String paymentId;
    private String orderId;
    private String reservationId;
    private BigDecimal amount;
    private String currency;
    private String transactionId;
    private String approvalNumber;
    private String gatewayName;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime processedAt;

    public static PaymentResponse success(String paymentId, String orderId, String reservationId,
                                          BigDecimal amount, String currency, String transactionId, String gatewayName) {
        return PaymentResponse.builder()
                .paymentId(paymentId)
                .orderId(orderId)
                .reservationId(reservationId)
                .amount(amount)
                .currency(currency)
                .transactionId(transactionId)
                .gatewayName(gatewayName)
                .status("COMPLETED")
                .message("Payment completed.")
                .processedAt(LocalDateTime.now())
                .timestamp(LocalDateTime.now())
                .build();
    }

    public static PaymentResponse failed(String paymentId, String reservationId,
                                         BigDecimal amount, String currency,
                                         String errorCode, String message) {
        return PaymentResponse.builder()
                .paymentId(paymentId)
                .reservationId(reservationId)
                .amount(amount)
                .currency(currency)
                .status("FAILED")
                .errorCode(errorCode)
                .message(message)
                .timestamp(LocalDateTime.now())
                .build();
    }
}

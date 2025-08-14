package com.example.payment.presentation.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 결제 완료 응답 DTO
 * 결제 완료 후 주문 ID 포함
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PaymentResponse {

    private String paymentId;
    private String orderId;                // 결제 완료 후 생성됨!
    private String reservationId;
    private BigDecimal amount;
    private String currency;

    // 상태 정보
    private String status;                 // COMPLETED, FAILED, ERROR
    private String message;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;

    // 에러 정보
    private String errorCode;
    private String errorMessage;

    // 고성능을 위한 팩토리 메서드들
    public static PaymentResponse completed(String paymentId, String orderId,
                                            String reservationId, BigDecimal amount, String currency) {
        return PaymentResponse.builder()
                .paymentId(paymentId)
                .orderId(orderId)
                .reservationId(reservationId)
                .amount(amount)
                .currency(currency)
                .status("COMPLETED")
                .message("결제가 완료되었습니다. 주문번호: " + orderId)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    public static PaymentResponse failed(String paymentId, String reservationId,
                                         BigDecimal amount, String currency,
                                         String errorCode, String errorMessage) {
        return PaymentResponse.builder()
                .paymentId(paymentId)
                .reservationId(reservationId)
                .amount(amount)
                .currency(currency)
                .status("FAILED")
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
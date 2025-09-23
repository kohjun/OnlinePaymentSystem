/**
 * 결제 전용 응답
 */
package com.example.payment.presentation.dto.response;

import com.example.payment.presentation.dto.common.BaseResponse;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import com.fasterxml.jackson.annotation.JsonFormat;
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

    // PG 관련 정보
    private String transactionId;
    private String approvalNumber;
    private String gatewayName;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime processedAt;

    // 헬퍼 메서드들
    public static PaymentResponse success(String paymentId, String orderId, String reservationId,
                                          BigDecimal amount, String currency, String transactionId, String s) {
        return PaymentResponse.builder()
                .paymentId(paymentId)
                .orderId(orderId)
                .reservationId(reservationId)
                .amount(amount)
                .currency(currency)
                .transactionId(transactionId)
                .status("COMPLETED")
                .message("결제가 완료되었습니다")
                .processedAt(LocalDateTime.now())
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
                .build();
    }
}
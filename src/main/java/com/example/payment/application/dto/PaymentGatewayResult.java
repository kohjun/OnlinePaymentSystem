/**
 * PG 게이트웨이 결과 DTO
 */
package com.example.payment.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.math.BigDecimal;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PaymentGatewayResult {
    private boolean success;
    private String transactionId;
    private String approvalNumber;
    private String errorCode;
    private String errorMessage;
    private BigDecimal processedAmount;
    private String currency;
    private LocalDateTime processedAt;
    private String gatewayName; // "TOSS", "KAKAO_PAY", "NAVER_PAY" 등

    /**
     * 성공 결과 생성
     */
    public static PaymentGatewayResult success(String transactionId, String approvalNumber,
                                               BigDecimal amount, String currency) {
        return PaymentGatewayResult.builder()
                .success(true)
                .transactionId(transactionId)
                .approvalNumber(approvalNumber)
                .processedAmount(amount)
                .currency(currency)
                .processedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 실패 결과 생성
     */
    public static PaymentGatewayResult failure(String errorCode, String errorMessage) {
        return PaymentGatewayResult.builder()
                .success(false)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .processedAt(LocalDateTime.now())
                .build();
    }
}
package com.example.payment.presentation.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PaymentResponse {

    private String paymentId;
    private String orderId;        // 결제 성공 시에만 생성
    private String reservationId;  // 예약 ID
    private BigDecimal amount;
    private String currency;
    private String status; // CREATED, PROCESSING, COMPLETED, FAILED, ERROR
    private String message;

    // PG 관련 정보
    private String transactionId;
    private String approvalNumber;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;

    /**
     * 실패 응답 생성 헬퍼
     */
    public static PaymentResponse failed(String paymentId, String reservationId,
                                         BigDecimal amount, String currency,
                                         String errorCode, String message) {
        return PaymentResponse.builder()
                .paymentId(paymentId)
                .reservationId(reservationId)
                .amount(amount)
                .currency(currency)
                .status("FAILED")
                .message(message)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 성공 응답 생성 헬퍼
     */
    public static PaymentResponse success(String paymentId, String orderId, String reservationId,
                                          BigDecimal amount, String currency,
                                          String transactionId, String approvalNumber) {
        return PaymentResponse.builder()
                .paymentId(paymentId)
                .orderId(orderId)
                .reservationId(reservationId)
                .amount(amount)
                .currency(currency)
                .status("COMPLETED")
                .message("결제가 완료되었습니다.")
                .transactionId(transactionId)
                .approvalNumber(approvalNumber)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 처리중 응답 생성 헬퍼
     */
    public static PaymentResponse processing(String paymentId, String reservationId,
                                             BigDecimal amount, String currency) {
        return PaymentResponse.builder()
                .paymentId(paymentId)
                .reservationId(reservationId)
                .amount(amount)
                .currency(currency)
                .status("PROCESSING")
                .message("결제 처리 중입니다.")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
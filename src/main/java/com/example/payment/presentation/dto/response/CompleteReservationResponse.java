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
 * 통합 예약 응답 DTO
 * - 예약, 주문, 결제 모든 정보 포함
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CompleteReservationResponse {

    // 예약 정보
    private String reservationId;
    private String productId;
    private Integer quantity;

    // 주문 정보
    private String orderId;
    private String customerId;

    // 결제 정보
    private String paymentId;
    private String transactionId;
    private BigDecimal amount;
    private String currency;

    // 상태 정보
    private String status; // SUCCESS, FAILED, PENDING, CANCELLED
    private String message;
    private String errorCode; // 실패 시에만

    // 시간 정보
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime completedAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime expiresAt; // 예약 만료 시간 (실패 시에만)

    // 추가 정보
    private String correlationId; // 추적용
    private Long remainingSeconds; // 예약 남은 시간 (실패 시에만)

    /**
     * 성공 응답 생성 헬퍼
     */
    public static CompleteReservationResponse success(String reservationId, String orderId, String paymentId,
                                                      String transactionId, String productId, Integer quantity,
                                                      BigDecimal amount, String currency, String message) {
        return CompleteReservationResponse.builder()
                .reservationId(reservationId)
                .orderId(orderId)
                .paymentId(paymentId)
                .transactionId(transactionId)
                .productId(productId)
                .quantity(quantity)
                .amount(amount)
                .currency(currency)
                .status("SUCCESS")
                .message(message)
                .completedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 실패 응답 생성 헬퍼
     */
    public static CompleteReservationResponse failed(String message) {
        return CompleteReservationResponse.builder()
                .status("FAILED")
                .message(message)
                .completedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 실패 응답 생성 헬퍼 (상세 정보 포함)
     */
    public static CompleteReservationResponse failed(String productId, Integer quantity,
                                                     String errorCode, String message) {
        return CompleteReservationResponse.builder()
                .productId(productId)
                .quantity(quantity)
                .status("FAILED")
                .errorCode(errorCode)
                .message(message)
                .completedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 보류 응답 생성 헬퍼 (예: 결제 승인 대기)
     */
    public static CompleteReservationResponse pending(String reservationId, String orderId, String paymentId,
                                                      String message) {
        return CompleteReservationResponse.builder()
                .reservationId(reservationId)
                .orderId(orderId)
                .paymentId(paymentId)
                .status("PENDING")
                .message(message)
                .completedAt(LocalDateTime.now())
                .build();
    }
}

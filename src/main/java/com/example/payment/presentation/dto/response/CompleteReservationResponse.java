/**
 * 통합 예약+결제 응답
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
public class CompleteReservationResponse extends BaseResponse {

    // 예약 정보
    private ReservationInfo reservation;

    // 주문 정보
    private OrderInfo order;

    // 결제 정보
    private PaymentInfo payment;

    @Data
    @SuperBuilder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReservationInfo {
        private String reservationId;
        private String productId;
        private Integer quantity;

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        private LocalDateTime expiresAt;
    }

    @Data
    @SuperBuilder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderInfo {
        private String orderId;
        private String customerId;
        private String status;

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        private LocalDateTime createdAt;
    }

    @Data
    @SuperBuilder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentInfo {
        private String paymentId;
        private String transactionId;
        private String approvalNumber;
        private BigDecimal amount;
        private String currency;
        private String status;

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        private LocalDateTime processedAt;
    }

    // 헬퍼 메서드들
    public static CompleteReservationResponse success(String reservationId, String orderId,
                                                      String paymentId, String transactionId,
                                                      String productId, Integer quantity,
                                                      BigDecimal amount, String currency) {
        return CompleteReservationResponse.builder()
                .reservation(ReservationInfo.builder()
                        .reservationId(reservationId)
                        .productId(productId)
                        .quantity(quantity)
                        .build())
                .order(OrderInfo.builder()
                        .orderId(orderId)
                        .status("CREATED")
                        .createdAt(LocalDateTime.now())
                        .build())
                .payment(PaymentInfo.builder()
                        .paymentId(paymentId)
                        .transactionId(transactionId)
                        .amount(amount)
                        .currency(currency)
                        .status("COMPLETED")
                        .processedAt(LocalDateTime.now())
                        .build())
                .status("SUCCESS")
                .message("통합 예약이 완료되었습니다")
                .build();
    }

    public static CompleteReservationResponse failed(String message) {
        return CompleteReservationResponse.builder()
                .status("FAILED")
                .message(message)
                .build();
    }
}
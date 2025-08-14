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
 * 재고 선점 응답 DTO
  */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OrderResponse {

    // 재고 선점 성공 시
    private String reservationId;           // 예약 ID (결제 시 필요)
    private String paymentId;              // 결제 ID (미리 생성)
    private String productId;
    private Integer quantity;
    private BigDecimal amount;
    private String currency;

    // 상태 정보
    private String status;                 // RESERVED, EXPIRED, FAILED
    private String message;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime expiresAt;       // 예약 만료 시간

    // 에러 정보
    private String errorCode;
    private String errorMessage;

    // 고성능을 위한 팩토리 메서드들
    public static OrderResponse reserved(String reservationId, String paymentId,
                                         String productId, Integer quantity,
                                         BigDecimal amount, String currency,
                                         LocalDateTime expiresAt) {
        return OrderResponse.builder()
                .reservationId(reservationId)
                .paymentId(paymentId)
                .productId(productId)
                .quantity(quantity)
                .amount(amount)
                .currency(currency)
                .status("RESERVED")
                .message("상품이 확보되었습니다. 5분 내에 결제를 완료해주세요.")
                .expiresAt(expiresAt)
                .build();
    }

    public static OrderResponse outOfStock(String productId) {
        return OrderResponse.builder()
                .productId(productId)
                .status("OUT_OF_STOCK")
                .errorCode("INSUFFICIENT_INVENTORY")
                .errorMessage("상품이 품절되었습니다")
                .build();
    }

    public static OrderResponse failed(String productId, String errorCode, String errorMessage) {
        return OrderResponse.builder()
                .productId(productId)
                .status("FAILED")
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .build();
    }
}
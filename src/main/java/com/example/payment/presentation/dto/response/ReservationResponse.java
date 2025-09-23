/**
 * 단순 예약 응답
 */
package com.example.payment.presentation.dto.response;

import com.example.payment.presentation.dto.common.BaseResponse;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ReservationResponse extends BaseResponse {

    // 예약 기본 정보
    private String reservationId;
    private String productId;
    private Integer quantity;

    // 시간 관련
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime expiresAt;
    private Long remainingSeconds;

    // 헬퍼 메서드들
    public static ReservationResponse success(String reservationId, String productId,
                                              Integer quantity, LocalDateTime expiresAt, Long remainingSeconds) {
        return ReservationResponse.builder()
                .reservationId(reservationId)
                .productId(productId)
                .quantity(quantity)
                .expiresAt(expiresAt)
                .remainingSeconds(remainingSeconds)
                .status("SUCCESS")
                .message("예약이 완료되었습니다")
                .build();
    }

    public static ReservationResponse failed(String productId, Integer quantity,
                                             String errorCode, String message) {
        return ReservationResponse.builder()
                .productId(productId)
                .quantity(quantity)
                .status("FAILED")
                .errorCode(errorCode)
                .message(message)
                .build();
    }
}
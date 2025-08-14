/**
 * 예약 상태 응답 DTO
 */
package com.example.payment.presentation.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReservationStatusResponse {

    private String reservationId;
    private String productId;
    private Integer quantity;
    private String status; // RESERVED, EXPIRED, CONFIRMED, CANCELLED, PROCESSING

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime expiresAt;

    private Long remainingSeconds;
    private String message;

    // 에러 정보 (실패 시에만)
    private String errorCode;

    /**
     * 성공 응답 생성
     */
    public static ReservationStatusResponse success(String reservationId, String productId,
                                                    Integer quantity, String status,
                                                    LocalDateTime expiresAt, Long remainingSeconds) {
        return ReservationStatusResponse.builder()
                .reservationId(reservationId)
                .productId(productId)
                .quantity(quantity)
                .status(status)
                .expiresAt(expiresAt)
                .remainingSeconds(remainingSeconds)
                .build();
    }

    /**
     * 실패 응답 생성
     */
    public static ReservationStatusResponse failed(String productId, Integer quantity,
                                                   String errorCode, String message) {
        return ReservationStatusResponse.builder()
                .productId(productId)
                .quantity(quantity)
                .status("FAILED")
                .errorCode(errorCode)
                .message(message)
                .remainingSeconds(0L)
                .build();
    }

    /**
     * 만료 응답 생성
     */
    public static ReservationStatusResponse expired(String reservationId, String productId, Integer quantity) {
        return ReservationStatusResponse.builder()
                .reservationId(reservationId)
                .productId(productId)
                .quantity(quantity)
                .status("EXPIRED")
                .remainingSeconds(0L)
                .message("예약이 만료되었습니다.")
                .build();
    }
}
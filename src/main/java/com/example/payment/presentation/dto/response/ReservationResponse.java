package com.example.payment.presentation.dto.response;

import com.example.payment.presentation.dto.common.BaseResponse;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ReservationResponse extends BaseResponse {

    private String reservationId;
    private String productId;
    private Integer quantity;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime expiresAt;

    private Long remainingSeconds;

    public static ReservationResponse success(String reservationId, String productId,
                                              Integer quantity, LocalDateTime expiresAt, Long remainingSeconds) {
        return ReservationResponse.builder()
                .reservationId(reservationId)
                .productId(productId)
                .quantity(quantity)
                .expiresAt(expiresAt)
                .remainingSeconds(remainingSeconds)
                .status("SUCCESS")
                .message("Reservation completed.")
                .timestamp(LocalDateTime.now())
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
                .timestamp(LocalDateTime.now())
                .build();
    }
}

package com.example.payment.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.example.payment.domain.model.ReservationState;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ReservationValidationResult {
    private boolean valid;
    private ReservationState reservation;
    private String errorCode;
    private String errorMessage;

    /**
     * 검증 성공 결과 생성
     */
    public static ReservationValidationResult valid(ReservationState reservation) {
        return ReservationValidationResult.builder()
                .valid(true)
                .reservation(reservation)
                .build();
    }

    /**
     * 검증 실패 결과 생성
     */
    public static ReservationValidationResult invalid(String errorCode, String errorMessage) {
        return ReservationValidationResult.builder()
                .valid(false)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .build();
    }

    /**
     * 검증 실패 결과 생성 (간단 버전)
     */
    public static ReservationValidationResult invalid(String errorMessage) {
        return invalid("VALIDATION_FAILED", errorMessage);
    }
}
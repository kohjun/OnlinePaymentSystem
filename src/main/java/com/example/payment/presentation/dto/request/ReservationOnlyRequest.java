/**
 * 단순 재고 예약 요청 (Phase 1만)
 */
package com.example.payment.presentation.dto.request;

import com.example.payment.presentation.dto.common.BaseReservationDto;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ReservationOnlyRequest extends BaseReservationDto {

    // 예약 전용 추가 필드들
    private Integer reservationTimeoutMinutes; // 예약 유지 시간 (기본 5분)
    private String reservationPurpose; // 예약 목적 (PURCHASE, HOLD 등)

    // 기본값 설정
    public Integer getReservationTimeoutMinutes() {
        return reservationTimeoutMinutes != null ? reservationTimeoutMinutes : 5;
    }
}
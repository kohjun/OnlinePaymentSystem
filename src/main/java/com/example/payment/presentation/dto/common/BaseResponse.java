/**
 * 기본 응답 (공통 필드들)
 */
package com.example.payment.presentation.dto.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public abstract class BaseResponse {

    protected String status; // SUCCESS, FAILED, PENDING 등
    protected String message;
    protected String errorCode; // 실패 시에만

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    protected LocalDateTime timestamp;

    protected String correlationId; // 추적용

    // 기본값 설정
    public BaseResponse() {
        this.timestamp = LocalDateTime.now();
    }
}
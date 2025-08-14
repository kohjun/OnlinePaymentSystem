package com.example.payment.presentation.dto.response;

import com.example.payment.domain.model.order.OrderItem;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Collections;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderResponse {
    private String orderId;
    private String customerId;
    private List<OrderItem> items;
    private BigDecimal totalAmount;
    private String currency;
    private String status;
    private String message;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;

    /**
     * 에러 응답 생성 헬퍼
     */
    public static OrderResponse error(String orderId, String message) {
        return OrderResponse.builder()
                .orderId(orderId)
                .customerId("UNKNOWN")
                .items(Collections.emptyList())
                .totalAmount(BigDecimal.ZERO)
                .currency("KRW")
                .status("ERROR")
                .message(message)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 성공 응답 생성 헬퍼
     */
    public static OrderResponse success(String orderId, String customerId,
                                        BigDecimal totalAmount, String currency, String status) {
        return OrderResponse.builder()
                .orderId(orderId)
                .customerId(customerId)
                .items(Collections.emptyList()) // 필요시 추가
                .totalAmount(totalAmount)
                .currency(currency)
                .status(status)
                .message("주문 처리가 완료되었습니다.")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 찾을 수 없음 응답 생성 헬퍼
     */
    public static OrderResponse notFound(String orderId) {
        return OrderResponse.builder()
                .orderId(orderId)
                .customerId("UNKNOWN")
                .items(Collections.emptyList())
                .totalAmount(BigDecimal.ZERO)
                .currency("KRW")
                .status("NOT_FOUND")
                .message("주문을 찾을 수 없습니다.")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}

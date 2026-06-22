package com.example.payment.presentation.dto.response;

import com.example.payment.domain.model.order.Order;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

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

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderItem {
        private String productId;
        private String productName;
        private Integer quantity;
        private BigDecimal unitPrice;
        private BigDecimal totalPrice;
    }

    public static OrderResponse success(String orderId, String customerId,
                                        BigDecimal totalAmount, String currency) {
        return OrderResponse.builder()
                .orderId(orderId)
                .customerId(customerId)
                .totalAmount(totalAmount)
                .currency(currency)
                .items(Collections.emptyList())
                .status("SUCCESS")
                .message("Order processed.")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    public static OrderResponse notFound(String orderId) {
        return OrderResponse.builder()
                .orderId(orderId)
                .customerId("UNKNOWN")
                .items(Collections.emptyList())
                .totalAmount(BigDecimal.ZERO)
                .currency("KRW")
                .status("NOT_FOUND")
                .message("Order not found.")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    public static OrderResponse error(String orderId, String errorMessage) {
        return OrderResponse.builder()
                .orderId(orderId)
                .customerId("UNKNOWN")
                .items(Collections.emptyList())
                .totalAmount(BigDecimal.ZERO)
                .currency("KRW")
                .status("ERROR")
                .message(errorMessage)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    public static OrderResponse from(Order order) {
        return OrderResponse.builder()
                .orderId(order.getOrderId())
                .customerId(order.getCustomerId())
                .items(Collections.emptyList())
                .totalAmount(order.getAmount().getAmount())
                .currency(order.getCurrency())
                .status(order.getStatus().name())
                .message("Order lookup completed.")
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .build();
    }

    public static OrderResponse withStatus(String orderId, String customerId, String status, String message) {
        return OrderResponse.builder()
                .orderId(orderId)
                .customerId(customerId)
                .items(Collections.emptyList())
                .totalAmount(BigDecimal.ZERO)
                .currency("KRW")
                .status(status)
                .message(message)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}

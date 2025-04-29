package com.example.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List; /**
 * 주문 요청 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderRequest {
    private String customerId;
    private String clientId;
    private List<OrderItem> items;
    private String currency;
    private String paymentMethod;
    private String idempotencyKey;
    private String shippingAddress;
}

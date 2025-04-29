package com.example.payment.event;

import com.example.payment.dto.OrderResponse;
import lombok.NoArgsConstructor;

/**
 * 주문 이벤트 클래스
 * - 주문 상태 변경을 위한 이벤트
 */
@NoArgsConstructor
public class OrderEvent extends BaseEvent<OrderResponse> {

    public static final String ORDER_CREATED = "ORDER_CREATED";
    public static final String ORDER_UPDATED = "ORDER_UPDATED";
    public static final String ORDER_CANCELLED = "ORDER_CANCELLED";
    public static final String PAYMENT_COMPLETED = "PAYMENT_COMPLETED";
    public static final String PAYMENT_FAILED = "PAYMENT_FAILED";

    public OrderEvent(String eventType, OrderResponse payload) {
        super(eventType, payload);
    }

    public OrderEvent(String eventType, OrderResponse payload, String correlationId) {
        super(eventType, payload, correlationId);
    }
}
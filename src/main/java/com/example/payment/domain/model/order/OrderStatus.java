package com.example.payment.domain.model.order;

public enum OrderStatus {
    CREATED("생성됨"),
    CONFIRMED("확정됨"),
    PAID("결제완료"),
    CANCELLED("취소됨"),
    SHIPPED("배송중"),
    DELIVERED("배송완료");

    private final String description;

    OrderStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
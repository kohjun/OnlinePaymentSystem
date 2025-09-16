package com.example.payment.domain.model.payment;

public enum PaymentStatus {
    PROCESSING("처리중"),
    COMPLETED("완료"),
    FAILED("실패"),
    REFUNDED("환불됨"),
    CANCELLED("취소됨");

    private final String description;

    PaymentStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
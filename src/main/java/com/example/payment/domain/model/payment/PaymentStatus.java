package com.example.payment.domain.model.payment;

public enum PaymentStatus {
    CREATED("created"),
    PROCESSING("processing"),
    APPROVED("approved"),
    COMPLETED("completed"),
    FAILED("failed"),
    UNKNOWN("unknown"),
    REFUNDED("refunded"),
    REFUND_FAILED("refund failed"),
    CANCELLED("cancelled");

    private final String description;

    PaymentStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}

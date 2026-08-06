package com.example.payment.domain.model.marketplace;

public enum OrderCancellationStatus {
    REQUESTED,
    REFUND_PROCESSING,
    COMPLETED,
    REFUND_UNKNOWN,
    REFUND_FAILED
}

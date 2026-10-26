package com.example.payment.application.dto;

public record PaymentRefundResult(Status status, PaymentGatewayResult payment, String message) {
    public enum Status { SUCCEEDED, FAILED, UNKNOWN }
    public static PaymentRefundResult unknown(String message) {
        return new PaymentRefundResult(Status.UNKNOWN, null, message);
    }
}

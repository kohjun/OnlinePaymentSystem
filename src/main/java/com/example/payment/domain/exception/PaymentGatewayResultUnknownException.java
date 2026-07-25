package com.example.payment.domain.exception;

public class PaymentGatewayResultUnknownException extends PaymentException {

    public PaymentGatewayResultUnknownException(String message, Throwable cause) {
        super(message, cause);
    }
}

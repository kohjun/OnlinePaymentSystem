package com.example.payment.domain.exception;

public class PaymentException extends DomainException {
    public PaymentException(String message) {
        super(message);
    }

    public PaymentException(String message, Throwable cause) {
        super(message, cause);
    }
}
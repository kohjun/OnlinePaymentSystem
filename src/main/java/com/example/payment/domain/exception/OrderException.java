package com.example.payment.domain.exception;

public class OrderException extends DomainException {
    public OrderException(String message) {
        super(message);
    }

    public OrderException(String message, Throwable cause) {
        super(message, cause);
    }
}
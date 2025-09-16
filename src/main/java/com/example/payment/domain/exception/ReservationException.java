package com.example.payment.domain.exception;

public class ReservationException extends DomainException {
    public ReservationException(String message) {
        super(message);
    }

    public ReservationException(String message, Throwable cause) {
        super(message, cause);
    }
}
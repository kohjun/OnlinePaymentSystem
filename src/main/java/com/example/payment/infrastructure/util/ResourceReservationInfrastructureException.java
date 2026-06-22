package com.example.payment.infrastructure.util;

public class ResourceReservationInfrastructureException extends RuntimeException {

    public ResourceReservationInfrastructureException(String message) {
        super(message);
    }

    public ResourceReservationInfrastructureException(String message, Throwable cause) {
        super(message, cause);
    }
}

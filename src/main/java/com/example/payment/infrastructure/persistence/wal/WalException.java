package com.example.payment.infrastructure.persistence.wal;

public class WalException extends RuntimeException {

    public WalException(String message) {
        super(message);
    }

    public WalException(String message, Throwable cause) {
        super(message, cause);
    }
}
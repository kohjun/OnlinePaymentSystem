package com.example.payment.application.service;

import org.springframework.http.HttpStatus;

public class MarketplaceCheckoutException extends RuntimeException {

    private final HttpStatus status;

    public MarketplaceCheckoutException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}

package com.example.payment.domain.event;

import com.example.payment.presentation.dto.response.PaymentResponse;
import lombok.NoArgsConstructor;

@NoArgsConstructor
public class PaymentEvent extends BaseEvent<PaymentResponse> {

    public static final String PAYMENT_CREATED = "PAYMENT_CREATED";
    public static final String PAYMENT_PROCESSED = "PAYMENT_PROCESSED";
    public static final String PAYMENT_FAILED = "PAYMENT_FAILED";

    public PaymentEvent(String eventType, PaymentResponse payload) {
        super(eventType, payload);
    }

    public PaymentEvent(String eventType, PaymentResponse payload, String correlationId) {
        super(eventType, payload, correlationId);
    }
}
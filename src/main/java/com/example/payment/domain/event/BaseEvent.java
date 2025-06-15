package com.example.payment.domain.event;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public abstract class BaseEvent<T> {
    private String eventId;
    private String eventType;
    private T payload;
    private LocalDateTime timestamp;
    private String correlationId;

    public BaseEvent() {
        this.eventId = java.util.UUID.randomUUID().toString();
        this.timestamp = LocalDateTime.now();
    }

    public BaseEvent(String eventType, T payload) {
        this();
        this.eventType = eventType;
        this.payload = payload;
    }

    public BaseEvent(String eventType, T payload, String correlationId) {
        this(eventType, payload);
        this.correlationId = correlationId;
    }
}
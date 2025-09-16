package com.example.payment.domain.model.reservation;

public enum ReservationStatus {
    RESERVED("예약됨"),
    CONFIRMED("확정됨"),
    CANCELLED("취소됨"),
    EXPIRED("만료됨");

    private final String description;

    ReservationStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
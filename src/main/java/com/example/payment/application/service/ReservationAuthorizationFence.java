package com.example.payment.application.service;

import com.example.payment.application.temporal.ReservationWorkflowCommand;
import com.example.payment.domain.entity.InventoryReservationRecord;
import com.example.payment.domain.repository.PaymentRecordRepository;
import com.example.payment.infrastructure.messaging.outbox.OutboxEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ReservationAuthorizationFence {
    private final PaymentRecordRepository payments;
    private final OutboxEventService outbox;

    // Both operations require the caller's reservation row lock and transaction.
    @Transactional(propagation = Propagation.MANDATORY)
    public void start(InventoryReservationRecord reservation, ReservationWorkflowCommand command) {
        requireOwnership(reservation, command);
        if (!Set.of("NOT_STARTED", "UNTRACKED").contains(reservation.getAuthorizationState())
                || reservation.getPaymentId() != null
                || payments.findByReservationId(reservation.getReservationId()).isPresent()) {
            throw new IllegalStateException("Authorization already started or permanently closed");
        }
        reservation.setAuthorizationState("STARTED");
        reservation.setAuthorizationStartedAt(LocalDateTime.now());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void closeBeforeAuthorization(InventoryReservationRecord reservation, ReservationWorkflowCommand command) {
        if (!"RESERVED".equals(reservation.getStatus()) || !"NOT_STARTED".equals(reservation.getAuthorizationState())) return;
        requireOwnership(reservation, command);
        if (reservation.getPaymentId() != null || payments.findByReservationId(reservation.getReservationId()).isPresent()) return;
        if (command.getWorkflowId() == null || command.getWorkflowId().isBlank()) {
            throw new IllegalArgumentException("Workflow identity is required for no-authorization proof");
        }
        reservation.setAuthorizationState("CLOSED_WITHOUT_AUTHORIZATION");
        reservation.setAuthorizationClosedAt(LocalDateTime.now());
        reservation.setAuthorizationCloseWorkflowId(command.getWorkflowId());
        outbox.record("RESERVATION", reservation.getReservationId(), "AUTHORIZATION_CLOSED_WITHOUT_SUBMISSION",
                "reservation-events", reservation.getProductId(), Map.of(
                        "reservationId", reservation.getReservationId(), "workflowId", command.getWorkflowId(),
                        "reason", "Workflow cancelled before durable authorization claim"));
    }

    private void requireOwnership(InventoryReservationRecord reservation, ReservationWorkflowCommand command) {
        if (!Objects.equals(reservation.getReservationId(), command.getReservationId())
                || !Objects.equals(reservation.getProductId(), command.getProductId())
                || !Objects.equals(reservation.getCustomerId(), command.getCustomerId())
                || !Objects.equals(reservation.getQuantity(), command.getQuantity())
                || !Objects.equals(reservation.getClaimedIntentId(), command.getTossIntentId())
                || (reservation.getOrderId() != null && !Objects.equals(reservation.getOrderId(), command.getOrderId()))) {
            throw new IllegalStateException("Authorization reservation ownership mismatch");
        }
    }
}

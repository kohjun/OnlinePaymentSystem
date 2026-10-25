package com.example.payment.application.service;

import com.example.payment.application.dto.PaymentGatewayResult;
import com.example.payment.application.temporal.ReservationWorkflowCommand;
import com.example.payment.domain.entity.PaymentRecord;
import com.example.payment.domain.repository.InventoryReservationRecordRepository;
import com.example.payment.domain.repository.PaymentRecordRepository;
import com.example.payment.infrastructure.messaging.outbox.OutboxEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PaymentObservationWriter {
    private final PaymentRecordRepository payments;
    private final InventoryReservationRecordRepository reservations;
    private final OutboxEventService outbox;
    private final ReservationAuthorizationFence authorizationFence;
    private static final Set<String> PROTECTED = Set.of("REFUNDED", "PARTIALLY_REFUNDED", "REFUND_UNKNOWN", "REFUND_FAILED");

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Claim begin(ReservationWorkflowCommand command) {
        // The reservation exists before payment; locking it also serializes the first insert with expiry.
        var reservation = reservations.findByIdForUpdate(command.getReservationId()).orElseThrow();
        PaymentRecord existing = payments.findByIdForUpdate(command.getPaymentId()).orElse(null);
        if (existing != null) return new Claim(existing, false);
        if (!Set.of("RESERVED", "CONFIRMED").contains(reservation.getStatus())) {
            throw new IllegalStateException("Inventory hold is not active");
        }
        authorizationFence.start(reservation, command);
        PaymentRecord payment = payments.saveAndFlush(PaymentRecord.builder()
                .paymentId(command.getPaymentId()).orderId(command.getOrderId())
                .reservationId(command.getReservationId()).customerId(command.getCustomerId())
                .amount(command.getAmount()).currency(command.getCurrency()).method(command.getPaymentMethod())
                .status("PROCESSING").createdAt(LocalDateTime.now()).build());
        reservation.setPaymentId(payment.getPaymentId());
        reservations.save(reservation);
        return new Claim(payment, true);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentRecord apply(ReservationWorkflowCommand command, PaymentGatewayResult observation) {
        PaymentRecord payment = payments.findByIdForUpdate(command.getPaymentId()).orElseThrow();
        if (PROTECTED.contains(payment.getStatus())) return payment;
        String previous = payment.getStatus();
        PaymentGatewayResult result = observation == null ? PaymentGatewayResult.unknown("Empty provider response") : observation;
        if (result.observation() != PaymentGatewayResult.Observation.UNKNOWN
                && (result.observation() != PaymentGatewayResult.Observation.REJECTED || result.getTransactionId() != null)
                && !matches(command, result)) {
            result = PaymentGatewayResult.unknown("PROVIDER_RESPONSE_MISMATCH: operator verification required");
        }
        String next = switch (result.observation()) {
            case APPROVED -> "APPROVED";
            case REJECTED -> "FAILED";
            // Cancellation receipts are finalized by the refund writer, never by an approval observation.
            case REFUNDED, PARTIALLY_REFUNDED, UNKNOWN -> "UNKNOWN";
        };
        if (Set.of("APPROVED", "COMPLETED").contains(previous) && !"APPROVED".equals(next)) return payment;
        payment.setStatus(next);
        payment.setProcessedAt(LocalDateTime.now());
        payment.setFailureReason("APPROVED".equals(next) ? null : result.getErrorMessage());
        if ("APPROVED".equals(next) || result.observation() == PaymentGatewayResult.Observation.REFUNDED
                || result.observation() == PaymentGatewayResult.Observation.PARTIALLY_REFUNDED) {
            payment.setTransactionId(result.getTransactionId());
            payment.setApprovalNumber(result.getApprovalNumber());
            payment.setGatewayName(result.getGatewayName());
        }
        payments.saveAndFlush(payment);
        if (!previous.equals(next)) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("workflowId", command.getWorkflowId());
            payload.put("correlationId", command.getCorrelationId());
            payload.put("reservationId", command.getReservationId());
            payload.put("paymentId", command.getPaymentId());
            payload.put("orderId", command.getOrderId());
            payload.put("status", next);
            outbox.record("PAYMENT", command.getPaymentId(), "APPROVED".equals(next) ? "PAYMENT_PROCESSED" : "PAYMENT_" + next,
                    "payment-events", command.getProductId(), payload);
        }
        return payment;
    }

    private boolean matches(ReservationWorkflowCommand command, PaymentGatewayResult result) {
        if (result.getProcessedAmount() == null || result.getProcessedAmount().compareTo(command.getAmount()) != 0
                || !Objects.equals(result.getCurrency(), command.getCurrency())) return false;
        if (command.getTossPaymentKey() == null) return result.getTransactionId() != null;
        return Objects.equals(command.getTossPaymentKey(), result.getTransactionId())
                && Objects.equals(command.getTossOrderId(), result.getOrderId());
    }

    public record Claim(PaymentRecord payment, boolean authorize) {}
}

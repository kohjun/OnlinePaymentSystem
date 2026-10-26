package com.example.payment.application.temporal;

import com.example.payment.application.dto.PaymentGatewayRequest;
import com.example.payment.application.dto.PaymentGatewayResult;
import com.example.payment.application.service.InventorySyncIssueService;
import com.example.payment.application.service.RefundCoordinator;
import com.example.payment.domain.entity.InventoryReservationRecord;
import com.example.payment.domain.entity.OrderRecord;
import com.example.payment.domain.entity.PaymentRecord;
import com.example.payment.domain.model.inventory.Inventory;
import com.example.payment.domain.repository.InventoryRepository;
import com.example.payment.domain.repository.InventoryReservationRecordRepository;
import com.example.payment.domain.repository.OrderRecordRepository;
import com.example.payment.domain.repository.PaymentRecordRepository;
import com.example.payment.domain.service.PaymentGatewayService;
import com.example.payment.infrastructure.gateway.PaymentGatewayFactory;
import com.example.payment.infrastructure.messaging.outbox.OutboxEventService;
import com.example.payment.infrastructure.util.ResourceReservationService;
import com.example.payment.presentation.dto.response.CompleteReservationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class CompleteReservationActivitiesImpl implements CompleteReservationActivities {

    private static final int RESERVATION_TTL_SECONDS = 300;

    private final ResourceReservationService resourceReservationService;
    private final InventoryRepository inventoryRepository;
    private final InventoryReservationRecordRepository reservationRepository;
    private final OrderRecordRepository orderRepository;
    private final PaymentRecordRepository paymentRepository;
    private final PaymentGatewayFactory paymentGatewayFactory;
    private final OutboxEventService outboxEventService;
    private final InventorySyncIssueService inventorySyncIssueService;
    private final RefundCoordinator refundCoordinator;
    private final com.example.payment.application.service.PaymentObservationWriter paymentWriter;
    private final com.example.payment.application.service.InventoryReleasePolicy releasePolicy;
    private final com.example.payment.application.service.RefundLedgerWriter refundWriter;
    private final com.example.payment.application.service.InventoryHoldService holds;

    @Override
    @Transactional
    public ReservationWorkflowStepResult reserveInventory(ReservationWorkflowCommand command) {
        InventoryReservationRecord existing = reservationRepository.findById(command.getReservationId()).orElse(null);
        if (existing != null && ("RESERVED".equals(existing.getStatus()) || "CONFIRMED".equals(existing.getStatus()))) {
            if (!command.getProductId().equals(existing.getProductId())
                    || !command.getCustomerId().equals(existing.getCustomerId())
                    || !command.getQuantity().equals(existing.getQuantity())) {
                return ReservationWorkflowStepResult.failure("INVENTORY_HOLD_CONFLICT");
            }
            if ("RESERVED".equals(existing.getStatus())
                    && !existing.getExpiresAt().isAfter(LocalDateTime.now())
                    && (command.getTossIntentId() == null || !command.getTossIntentId().equals(existing.getClaimedIntentId()))) {
                return ReservationWorkflowStepResult.failure("INVENTORY_HOLD_EXPIRED");
            }
            return ReservationWorkflowStepResult.builder()
                    .success(true)
                    .reservationId(existing.getReservationId())
                    .expiresAt(existing.getExpiresAt())
                    .status(existing.getStatus())
                    .message("reservation already exists")
                    .build();
        }

        boolean reserved = resourceReservationService.reserveResource(
                inventoryKey(command),
                command.getQuantity(),
                Duration.ofSeconds(RESERVATION_TTL_SECONDS),
                command.getReservationId()
        );

        if (!reserved) {
            return ReservationWorkflowStepResult.failure("재고 선점 실패: 재고가 부족합니다");
        }

        LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(RESERVATION_TTL_SECONDS);
        InventoryReservationRecord record;
        try {
            record = reservationRepository.saveAndFlush(InventoryReservationRecord.builder()
                    .reservationId(command.getReservationId())
                    .productId(command.getProductId())
                    .customerId(command.getCustomerId())
                    .quantity(command.getQuantity())
                    .seatId(command.getSeatId())
                    .status("RESERVED")
                    .authorizationState("NOT_STARTED")
                    .expiresAt(expiresAt)
                    .createdAt(LocalDateTime.now())
                    .build());

            Inventory inventory = inventoryRepository.findByIdForUpdate(command.getProductId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Inventory not found: " + command.getProductId()));
            if (inventory.getAvailableQuantity() < command.getQuantity()) {
                throw new IllegalStateException("Postgres inventory is lower than the Redis reservation result.");
            }
            inventory.setAvailableQuantity(inventory.getAvailableQuantity() - command.getQuantity());
            inventory.setReservedQuantity(inventory.getReservedQuantity() + command.getQuantity());
            inventoryRepository.saveAndFlush(inventory);
        } catch (RuntimeException databaseFailure) {
            try {
                resourceReservationService.releaseResource(
                        inventoryKey(command), command.getQuantity(), command.getReservationId());
            } catch (RuntimeException compensationFailure) {
                databaseFailure.addSuppressed(compensationFailure);
                recordInventoryIssue(command, "RESERVE_RELEASE_REQUIRED", compensationFailure);
            }
            throw databaseFailure;
        }

        outboxEventService.record("RESERVATION", command.getReservationId(), "RESERVATION_CREATED",
                "reservation-events", command.getProductId(), payload(command, "RESERVATION_CREATED"));

        return ReservationWorkflowStepResult.builder()
                .success(true)
                .reservationId(record.getReservationId())
                .expiresAt(record.getExpiresAt())
                .status(record.getStatus())
                .message("reservation created")
                .build();
    }

    @Override
    @Transactional
    public ReservationWorkflowStepResult createOrder(ReservationWorkflowCommand command) {
        var hold = reservationRepository.findByIdForUpdate(command.getReservationId()).orElseThrow();
        if (!java.util.Set.of("RESERVED", "CONFIRMED").contains(hold.getStatus())
                || "CLOSED_WITHOUT_AUTHORIZATION".equals(hold.getAuthorizationState())) {
            return ReservationWorkflowStepResult.failure("Inventory hold is no longer available for order creation");
        }
        OrderRecord existing = orderRepository.findById(command.getOrderId()).orElse(null);
        if (existing != null) {
            if ("CANCELLED".equals(existing.getStatus())) return ReservationWorkflowStepResult.failure("Order already cancelled");
            return ReservationWorkflowStepResult.builder()
                    .success(true)
                    .orderId(existing.getOrderId())
                    .status(existing.getStatus())
                    .message("order already exists")
                    .build();
        }

        if (!"RESERVED".equals(hold.getStatus())) return ReservationWorkflowStepResult.failure("Inventory is already confirmed");
        OrderRecord order = orderRepository.save(OrderRecord.builder()
                .orderId(command.getOrderId())
                .customerId(command.getCustomerId())
                .productId(command.getProductId())
                .reservationId(command.getReservationId())
                .quantity(command.getQuantity())
                .amount(command.getAmount())
                .unitPrice(command.getUnitPrice())
                .priceSource(command.getPriceSource())
                .priceCalculatedAt(command.getPriceCalculatedAt())
                .currency(command.getCurrency())
                .seatId(command.getSeatId())
                .status("CREATED")
                .createdAt(LocalDateTime.now())
                .build());

        reservationRepository.findById(command.getReservationId()).ifPresent(reservation -> {
            reservation.setOrderId(command.getOrderId());
            reservationRepository.save(reservation);
        });

        outboxEventService.record("ORDER", command.getOrderId(), "ORDER_CREATED",
                "order-events", command.getProductId(), payload(command, "ORDER_CREATED"));

        return ReservationWorkflowStepResult.builder()
                .success(true)
                .orderId(order.getOrderId())
                .status(order.getStatus())
                .message("order created")
                .build();
    }

    @Override
    public ReservationWorkflowStepResult processPayment(ReservationWorkflowCommand command) {
        var claim = paymentWriter.begin(command);
        if (!claim.authorize()) {
            if (isApprovedPayment(claim.payment().getStatus()) || "FAILED".equals(claim.payment().getStatus())) {
                return paymentResult(claim.payment(), isApprovedPayment(claim.payment().getStatus()), "existing payment");
            }
            return verifyPaymentStatus(command);
        }
        PaymentGatewayResult observation;
        try {
            observation = paymentGatewayFactory.getGateway(command.getPaymentMethod()).authorize(PaymentGatewayRequest.builder()
                    .paymentId(command.getPaymentId()).idempotencyKey(command.getPaymentId())
                    .customerId(command.getCustomerId()).amount(command.getAmount()).currency(command.getCurrency())
                    .method(command.getPaymentMethod()).tossPaymentKey(command.getTossPaymentKey())
                    .tossOrderId(command.getTossOrderId()).tossIntentId(command.getTossIntentId())
                    .orderName("EverySale").build());
        } catch (Exception failure) {
            observation = PaymentGatewayResult.unknown("Provider authorization result unavailable");
        }
        // Persistence errors must escape to Temporal; they are not evidence of provider rejection.
        PaymentRecord payment = paymentWriter.apply(command, observation);
        if (observation != null && (observation.observation() == PaymentGatewayResult.Observation.REFUNDED
                || observation.observation() == PaymentGatewayResult.Observation.PARTIALLY_REFUNDED)) {
            payment = refundWriter.observe(command.getPaymentId(), observation, null, false).payment();
        }
        return paymentResult(payment, isApprovedPayment(payment.getStatus()), payment.getFailureReason());
    }

    @Override
    @Transactional
    public ReservationWorkflowStepResult confirmInventory(ReservationWorkflowCommand command) {
        InventoryReservationRecord reservation = reservationRepository.findByIdForUpdate(command.getReservationId()).orElse(null);
        if (reservation == null) {
            return ReservationWorkflowStepResult.failure("예약 정보를 찾을 수 없어 확정 실패");
        }
        if ("CONFIRMED".equals(reservation.getStatus())) {
            return ReservationWorkflowStepResult.success("reservation already confirmed");
        }

        boolean confirmed = resourceReservationService.confirmResource(
                inventoryKey(command),
                command.getQuantity(),
                command.getReservationId()
        );

        if (!confirmed) {
            return ReservationWorkflowStepResult.failure("Redis 예약 확정 실패");
        }

        try {
            reservation.setStatus("CONFIRMED");
            reservation.setOrderId(command.getOrderId());
            reservation.setPaymentId(command.getPaymentId());
            reservationRepository.saveAndFlush(reservation);

            Inventory inventory = inventoryRepository.findByIdForUpdate(command.getProductId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Inventory not found: " + command.getProductId()));
            inventory.setReservedQuantity(Math.max(0, inventory.getReservedQuantity() - command.getQuantity()));
            inventoryRepository.saveAndFlush(inventory);
        } catch (RuntimeException databaseFailure) {
            recordInventoryIssue(command, "CONFIRM_DB_APPLY_REQUIRED", databaseFailure);
            throw databaseFailure;
        }

        outboxEventService.record("RESERVATION", command.getReservationId(), "RESERVATION_CONFIRMED",
                "reservation-events", command.getProductId(), payload(command, "RESERVATION_CONFIRMED"));
        return ReservationWorkflowStepResult.success("inventory confirmed");
    }

    @Override
    @Transactional
    public ReservationWorkflowStepResult markOrderPaid(ReservationWorkflowCommand command) {
        OrderRecord order = orderRepository.findById(command.getOrderId()).orElse(null);
        if (order == null) {
            return ReservationWorkflowStepResult.failure("주문 정보를 찾을 수 없습니다");
        }
        if ("PAID".equals(order.getStatus())) {
            return ReservationWorkflowStepResult.success("order already paid");
        }

        order.setStatus("PAID");
        order.setPaymentId(command.getPaymentId());
        orderRepository.save(order);

        outboxEventService.record("ORDER", command.getOrderId(), "ORDER_PAID",
                "order-events", command.getProductId(), payload(command, "ORDER_PAID"));
        return ReservationWorkflowStepResult.success("order paid");
    }

    @Override
    @Transactional
    public void cancelReservation(ReservationWorkflowCommand command, String reason) {
        if (!holds.compensate(command)) return;

        // A late createOrder retry may have committed between the two compensation activities.
        cancelOrderLocked(command, reason);

        outboxEventService.record("RESERVATION", command.getReservationId(), "RESERVATION_CANCELLED",
                "reservation-events", command.getProductId(), payload(command, "RESERVATION_CANCELLED", reason));
    }

    @Override
    @Transactional
    public void cancelOrder(ReservationWorkflowCommand command, String reason) {
        reservationRepository.findByIdForUpdate(command.getReservationId());
        cancelOrderLocked(command, reason);
    }

    private void cancelOrderLocked(ReservationWorkflowCommand command, String reason) {
        orderRepository.findById(command.getOrderId()).ifPresent(order -> {
            if (!"CANCELLED".equals(order.getStatus())) {
                order.setStatus("CANCELLED");
                orderRepository.save(order);
                outboxEventService.record("ORDER", command.getOrderId(), "ORDER_CANCELLED",
                        "order-events", command.getProductId(), payload(command, "ORDER_CANCELLED", reason));
            }
        });
    }

    @Override
    public RefundCompensationResult refundPayment(ReservationWorkflowCommand command, String reason) {
        RefundCoordinator.RefundOutcome outcome = refundCoordinator.refund(
                command.getPaymentId(), "saga-" + command.getPaymentId(), reason);
        return RefundCompensationResult.builder()
                .successful(outcome.success())
                .status(outcome.code())
                .message(outcome.message())
                .build();
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void recordCompensationFailure(ReservationWorkflowCommand command, String activityName, String reason) {
        Map<String, Object> payload = payload(command, "COMPENSATION_FAILED", reason);
        payload.put("failedActivity", activityName);
        outboxEventService.record("WORKFLOW", command.getWorkflowId(), "COMPENSATION_FAILED_" + activityName,
                "reservation-events", command.getProductId(), payload);
    }

    @Override
    @Transactional(readOnly = true)
    public CompleteReservationResponse buildSuccessResponse(ReservationWorkflowCommand command) {
        InventoryReservationRecord reservation = reservationRepository.findById(command.getReservationId()).orElse(null);
        OrderRecord order = orderRepository.findById(command.getOrderId()).orElse(null);
        PaymentRecord payment = paymentRepository.findById(command.getPaymentId()).orElse(null);

        return CompleteReservationResponse.builder()
                .workflowId(command.getWorkflowId())
                .status("SUCCESS")
                .message("예약이 완료되었습니다")
                .correlationId(command.getCorrelationId())
                .reservation(CompleteReservationResponse.ReservationInfo.builder()
                        .reservationId(command.getReservationId())
                        .productId(command.getProductId())
                        .quantity(command.getQuantity())
                        .expiresAt(reservation != null ? reservation.getExpiresAt() : null)
                        .build())
                .order(CompleteReservationResponse.OrderInfo.builder()
                        .orderId(command.getOrderId())
                        .customerId(command.getCustomerId())
                        .status(order != null ? order.getStatus() : "PAID")
                        .createdAt(order != null ? order.getCreatedAt() : null)
                        .build())
                .payment(CompleteReservationResponse.PaymentInfo.builder()
                        .paymentId(command.getPaymentId())
                        .transactionId(payment != null ? payment.getTransactionId() : null)
                        .approvalNumber(payment != null ? payment.getApprovalNumber() : null)
                        .amount(command.getAmount())
                        .currency(command.getCurrency())
                        .status(payment != null ? payment.getStatus() : "COMPLETED")
                        .processedAt(payment != null ? payment.getProcessedAt() : null)
                        .build())
                .build();
    }

    @Override
    public CompleteReservationResponse buildFailureResponse(ReservationWorkflowCommand command, String message) {
        return CompleteReservationResponse.builder()
                .workflowId(command.getWorkflowId())
                .status("FAILED")
                .message(message)
                .correlationId(command.getCorrelationId())
                .build();
    }

    private String inventoryKey(ReservationWorkflowCommand command) {
        return "inventory:" + command.getProductId();
    }

    private ReservationWorkflowStepResult paymentResult(PaymentRecord payment, boolean success, String message) {
        return ReservationWorkflowStepResult.builder()
                .success(success)
                .message(message)
                .paymentId(payment.getPaymentId())
                .orderId(payment.getOrderId())
                .reservationId(payment.getReservationId())
                .transactionId(payment.getTransactionId())
                .approvalNumber(payment.getApprovalNumber())
                .gatewayName(payment.getGatewayName())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .status(payment.getStatus())
                .processedAt(payment.getProcessedAt())
                .build();
    }

    private boolean isApprovedPayment(String status) {
        return "APPROVED".equals(status) || "COMPLETED".equals(status);
    }

    @Override
    public ReservationWorkflowStepResult verifyPaymentStatus(ReservationWorkflowCommand command) {
        paymentWriter.begin(command);
        PaymentGatewayResult observation;
        try {
            PaymentGatewayService gateway = paymentGatewayFactory.getGateway(command.getPaymentMethod());
            observation = command.getTossPaymentKey() != null && !command.getTossPaymentKey().isBlank()
                    ? gateway.getPaymentStatus(command.getTossPaymentKey())
                    : gateway.getPaymentStatusByPaymentId(command.getTossOrderId() != null ? command.getTossOrderId() : command.getOrderId());
        } catch (Exception failure) {
            observation = PaymentGatewayResult.unknown("Provider status lookup unavailable");
        }
        PaymentRecord payment = paymentWriter.apply(command, observation);
        if (observation != null && (observation.observation() == PaymentGatewayResult.Observation.REFUNDED
                || observation.observation() == PaymentGatewayResult.Observation.PARTIALLY_REFUNDED)) {
            payment = refundWriter.observe(command.getPaymentId(), observation, null, false).payment();
        }
        return paymentResult(payment, isApprovedPayment(payment.getStatus()), payment.getFailureReason());
    }

    private Map<String, Object> payload(ReservationWorkflowCommand command, String eventType) {
        return payload(command, eventType, null);
    }

    private void recordInventoryIssue(ReservationWorkflowCommand command,
                                      String issueType,
                                      RuntimeException failure) {
        try {
            inventorySyncIssueService.record(
                    command.getReservationId(),
                    command.getProductId(),
                    command.getQuantity(),
                    issueType,
                    failure
            );
        } catch (RuntimeException issueWriteFailure) {
            failure.addSuppressed(issueWriteFailure);
            log.error("Failed to persist inventory synchronization issue: reservationId={}, issueType={}",
                    command.getReservationId(), issueType, issueWriteFailure);
        }
    }

    private Map<String, Object> payload(ReservationWorkflowCommand command, String eventType, String reason) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("eventType", eventType);
        payload.put("workflowId", command.getWorkflowId());
        payload.put("correlationId", command.getCorrelationId());
        payload.put("reservationId", command.getReservationId());
        payload.put("orderId", command.getOrderId());
        payload.put("paymentId", command.getPaymentId());
        payload.put("productId", command.getProductId());
        payload.put("customerId", command.getCustomerId());
        payload.put("quantity", command.getQuantity());
        payload.put("amount", command.getAmount());
        payload.put("currency", command.getCurrency());
        payload.put("reason", reason);
        payload.put("createdAt", LocalDateTime.now().toString());
        return payload;
    }
}

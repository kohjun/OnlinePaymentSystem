package com.example.payment.application.temporal;

import com.example.payment.application.dto.PaymentGatewayRequest;
import com.example.payment.application.dto.PaymentGatewayResult;
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

    @Override
    @Transactional
    public ReservationWorkflowStepResult reserveInventory(ReservationWorkflowCommand command) {
        InventoryReservationRecord existing = reservationRepository.findById(command.getReservationId()).orElse(null);
        if (existing != null && ("RESERVED".equals(existing.getStatus()) || "CONFIRMED".equals(existing.getStatus()))) {
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
        InventoryReservationRecord record = reservationRepository.save(InventoryReservationRecord.builder()
                .reservationId(command.getReservationId())
                .productId(command.getProductId())
                .customerId(command.getCustomerId())
                .quantity(command.getQuantity())
                .status("RESERVED")
                .expiresAt(expiresAt)
                .createdAt(LocalDateTime.now())
                .build());

        inventoryRepository.findById(command.getProductId()).ifPresent(inventory -> {
            inventory.setAvailableQuantity(inventory.getAvailableQuantity() - command.getQuantity());
            inventory.setReservedQuantity(inventory.getReservedQuantity() + command.getQuantity());
            inventoryRepository.save(inventory);
        });

        outboxEventService.record("RESERVATION", command.getReservationId(), "RESERVATION_CREATED",
                "reservation-events", command.getReservationId(), payload(command, "RESERVATION_CREATED"));

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
        OrderRecord existing = orderRepository.findById(command.getOrderId()).orElse(null);
        if (existing != null) {
            return ReservationWorkflowStepResult.builder()
                    .success(true)
                    .orderId(existing.getOrderId())
                    .status(existing.getStatus())
                    .message("order already exists")
                    .build();
        }

        OrderRecord order = orderRepository.save(OrderRecord.builder()
                .orderId(command.getOrderId())
                .customerId(command.getCustomerId())
                .productId(command.getProductId())
                .reservationId(command.getReservationId())
                .quantity(command.getQuantity())
                .amount(command.getAmount())
                .currency(command.getCurrency())
                .status("CREATED")
                .createdAt(LocalDateTime.now())
                .build());

        reservationRepository.findById(command.getReservationId()).ifPresent(reservation -> {
            reservation.setOrderId(command.getOrderId());
            reservationRepository.save(reservation);
        });

        outboxEventService.record("ORDER", command.getOrderId(), "ORDER_CREATED",
                "order-events", command.getOrderId(), payload(command, "ORDER_CREATED"));

        return ReservationWorkflowStepResult.builder()
                .success(true)
                .orderId(order.getOrderId())
                .status(order.getStatus())
                .message("order created")
                .build();
    }

    @Override
    public ReservationWorkflowStepResult processPayment(ReservationWorkflowCommand command) {
        PaymentRecord existing = paymentRepository.findById(command.getPaymentId()).orElse(null);
        if (existing != null && "COMPLETED".equals(existing.getStatus())) {
            ensurePaymentSideEffects(command, existing);
            return paymentResult(existing, true, "payment already completed");
        }
        if (existing != null && "FAILED".equals(existing.getStatus())) {
            return paymentResult(existing, false, "payment already failed: " + existing.getFailureReason());
        }

        PaymentRecord payment = existing != null ? existing : paymentRepository.save(PaymentRecord.builder()
                .paymentId(command.getPaymentId())
                .orderId(command.getOrderId())
                .reservationId(command.getReservationId())
                .customerId(command.getCustomerId())
                .amount(command.getAmount())
                .currency(command.getCurrency())
                .method(command.getPaymentMethod())
                .status("PROCESSING")
                .createdAt(LocalDateTime.now())
                .build());

        PaymentGatewayService gateway = paymentGatewayFactory.getGateway(command.getPaymentMethod());
        PaymentGatewayResult gatewayResult = gateway.processPayment(PaymentGatewayRequest.builder()
                .paymentId(command.getPaymentId())
                .customerId(command.getCustomerId())
                .amount(command.getAmount())
                .currency(command.getCurrency())
                .method(command.getPaymentMethod())
                .orderName("온라인 상품 결제")
                .build());

        if (gatewayResult.isSuccess()) {
            payment.setStatus("COMPLETED");
            payment.setTransactionId(gatewayResult.getTransactionId());
            payment.setApprovalNumber(gatewayResult.getApprovalNumber());
            payment.setGatewayName(gatewayResult.getGatewayName());
            payment.setProcessedAt(LocalDateTime.now());
            payment.setFailureReason(null);
            paymentRepository.save(payment);

            ensurePaymentSideEffects(command, payment);
            return paymentResult(payment, true, "payment completed");
        }

        payment.setStatus("FAILED");
        payment.setFailureReason(gatewayResult.getErrorMessage());
        payment.setProcessedAt(LocalDateTime.now());
        paymentRepository.save(payment);

        outboxEventService.record("PAYMENT", command.getPaymentId(), "PAYMENT_FAILED",
                "payment-events", command.getPaymentId(), payload(command, "PAYMENT_FAILED"));
        return paymentResult(payment, false, "결제 실패: " + gatewayResult.getErrorMessage());
    }

    @Override
    @Transactional
    public ReservationWorkflowStepResult confirmInventory(ReservationWorkflowCommand command) {
        InventoryReservationRecord reservation = reservationRepository.findById(command.getReservationId()).orElse(null);
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

        reservation.setStatus("CONFIRMED");
        reservation.setOrderId(command.getOrderId());
        reservation.setPaymentId(command.getPaymentId());
        reservationRepository.save(reservation);

        inventoryRepository.findById(command.getProductId()).ifPresent(inventory -> {
            inventory.setReservedQuantity(Math.max(0, inventory.getReservedQuantity() - command.getQuantity()));
            inventoryRepository.save(inventory);
        });

        outboxEventService.record("RESERVATION", command.getReservationId(), "RESERVATION_CONFIRMED",
                "reservation-events", command.getReservationId(), payload(command, "RESERVATION_CONFIRMED"));
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
                "order-events", command.getOrderId(), payload(command, "ORDER_PAID"));
        return ReservationWorkflowStepResult.success("order paid");
    }

    @Override
    @Transactional
    public void cancelReservation(ReservationWorkflowCommand command, String reason) {
        InventoryReservationRecord reservation = reservationRepository.findById(command.getReservationId()).orElse(null);
        if (reservation == null || "CANCELLED".equals(reservation.getStatus())) {
            return;
        }

        String previousStatus = reservation.getStatus();
        boolean released = resourceReservationService.releaseResource(
                inventoryKey(command),
                command.getQuantity(),
                command.getReservationId()
        );

        if (!released) {
            log.warn("Redis reservation release failed: reservationId={}", command.getReservationId());
            throw new IllegalStateException("Redis reservation release failed: " + command.getReservationId());
        }

        reservation.setStatus("CANCELLED");
        reservationRepository.save(reservation);

        inventoryRepository.findById(command.getProductId()).ifPresent(inventory -> {
            inventory.setAvailableQuantity(inventory.getAvailableQuantity() + command.getQuantity());
            if ("RESERVED".equals(previousStatus)) {
                inventory.setReservedQuantity(Math.max(0, inventory.getReservedQuantity() - command.getQuantity()));
            }
            inventoryRepository.save(inventory);
        });

        outboxEventService.record("RESERVATION", command.getReservationId(), "RESERVATION_CANCELLED",
                "reservation-events", command.getReservationId(), payload(command, "RESERVATION_CANCELLED", reason));
    }

    @Override
    @Transactional
    public void cancelOrder(ReservationWorkflowCommand command, String reason) {
        orderRepository.findById(command.getOrderId()).ifPresent(order -> {
            if (!"CANCELLED".equals(order.getStatus())) {
                order.setStatus("CANCELLED");
                orderRepository.save(order);
                outboxEventService.record("ORDER", command.getOrderId(), "ORDER_CANCELLED",
                        "order-events", command.getOrderId(), payload(command, "ORDER_CANCELLED", reason));
            }
        });
    }

    @Override
    @Transactional
    public void refundPayment(ReservationWorkflowCommand command, String reason) {
        PaymentRecord payment = paymentRepository.findById(command.getPaymentId()).orElse(null);
        if (payment == null || "REFUNDED".equals(payment.getStatus()) || !"COMPLETED".equals(payment.getStatus())) {
            return;
        }

        PaymentGatewayService gateway = paymentGatewayFactory.getGateway(payment.getMethod());
        boolean refunded = gateway.refundPayment(payment.getTransactionId());
        if (refunded) {
            payment.setStatus("REFUNDED");
            payment.setProcessedAt(LocalDateTime.now());
            paymentRepository.save(payment);
            outboxEventService.record("PAYMENT", command.getPaymentId(), "PAYMENT_REFUNDED",
                    "payment-events", command.getPaymentId(), payload(command, "PAYMENT_REFUNDED", reason));
        }
    }

    @Override
    @Transactional
    public void recordCompensationFailure(ReservationWorkflowCommand command, String activityName, String reason) {
        Map<String, Object> payload = payload(command, "COMPENSATION_FAILED", reason);
        payload.put("failedActivity", activityName);
        outboxEventService.record("WORKFLOW", command.getWorkflowId(), "COMPENSATION_FAILED_" + activityName,
                "reservation-events", command.getWorkflowId(), payload);
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

    @Transactional
    protected void ensurePaymentSideEffects(ReservationWorkflowCommand command, PaymentRecord payment) {
        reservationRepository.findById(command.getReservationId()).ifPresent(reservation -> {
            if (reservation.getPaymentId() == null) {
                reservation.setPaymentId(command.getPaymentId());
                reservationRepository.save(reservation);
            }
        });

        outboxEventService.record("PAYMENT", command.getPaymentId(), "PAYMENT_PROCESSED",
                "payment-events", command.getPaymentId(), payload(command, "PAYMENT_PROCESSED"));
    }

    private Map<String, Object> payload(ReservationWorkflowCommand command, String eventType) {
        return payload(command, eventType, null);
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

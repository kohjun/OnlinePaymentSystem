package com.example.payment.application.temporal;

import com.example.payment.application.dto.PaymentGatewayRequest;
import com.example.payment.application.dto.PaymentGatewayResult;
import com.example.payment.domain.entity.InventoryReservationRecord;
import com.example.payment.domain.entity.OrderRecord;
import com.example.payment.domain.entity.PaymentRecord;
import com.example.payment.domain.entity.RefundRecord;
import com.example.payment.domain.model.inventory.Inventory;
import com.example.payment.domain.repository.InventoryRepository;
import com.example.payment.domain.repository.InventoryReservationRecordRepository;
import com.example.payment.domain.repository.OrderRecordRepository;
import com.example.payment.domain.repository.PaymentRecordRepository;
import com.example.payment.domain.repository.RefundRecordRepository;
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
    private final RefundRecordRepository refundRepository;
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
                .seatId(command.getSeatId())
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
        PaymentRecord existing = paymentRepository.findById(command.getPaymentId()).orElse(null);
        if (existing != null && isApprovedPayment(existing.getStatus())) {
            ensurePaymentSideEffects(command, existing);
            return paymentResult(existing, true, "payment already completed");
        }
        if (existing != null && "FAILED".equals(existing.getStatus())) {
            return paymentResult(existing, false, "payment already failed: " + existing.getFailureReason());
        }
        if (existing != null && ("PROCESSING".equals(existing.getStatus()) || "UNKNOWN".equals(existing.getStatus()))) {
            log.warn("Payment status is {}, verifying provider status before retry: paymentId={}",
                    existing.getStatus(), command.getPaymentId());
            return verifyPaymentStatus(command);
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

        PaymentGatewayResult gatewayResult;
        try {
            PaymentGatewayService gateway = paymentGatewayFactory.getGateway(command.getPaymentMethod());
            gatewayResult = gateway.authorize(PaymentGatewayRequest.builder()
                .paymentId(command.getPaymentId())
                .idempotencyKey(command.getPaymentId())
                .customerId(command.getCustomerId())
                .amount(command.getAmount())
                .currency(command.getCurrency())
                .method(command.getPaymentMethod())
                .tossPaymentKey(command.getTossPaymentKey())
                .tossOrderId(command.getTossOrderId())
                .tossIntentId(command.getTossIntentId())
                .orderName("온라인 상품 결제")
                .build());
        } catch (IllegalArgumentException e) {
            payment.setStatus("FAILED");
            payment.setFailureReason(e.getMessage());
            payment.setProcessedAt(LocalDateTime.now());
            paymentRepository.save(payment);
            return paymentResult(payment, false, e.getMessage());
        } catch (Exception e) {
            payment.setStatus("UNKNOWN");
            payment.setFailureReason("PG result unknown: " + e.getMessage());
            payment.setProcessedAt(LocalDateTime.now());
            paymentRepository.save(payment);
            return paymentResult(payment, false, "PG result unknown; provider status verification required");
        }

        if (gatewayResult.isSuccess()) {
            payment.setStatus("APPROVED");
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
                "payment-events", command.getProductId(), payload(command, "PAYMENT_FAILED"));
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
                "reservation-events", command.getProductId(), payload(command, "RESERVATION_CANCELLED", reason));
    }

    @Override
    @Transactional
    public void cancelOrder(ReservationWorkflowCommand command, String reason) {
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
    @Transactional
    public void refundPayment(ReservationWorkflowCommand command, String reason) {
        PaymentRecord payment = paymentRepository.findById(command.getPaymentId()).orElse(null);
        if (payment == null || "REFUNDED".equals(payment.getStatus()) || !isApprovedPayment(payment.getStatus())) {
            return;
        }

        String refundId = "RF-" + command.getPaymentId();
        RefundRecord refund = refundRepository.findByPaymentIdAndIdempotencyKey(payment.getPaymentId(), refundId)
                .orElseGet(() -> refundRepository.save(RefundRecord.builder()
                        .refundId(refundId)
                        .paymentId(payment.getPaymentId())
                        .idempotencyKey(refundId)
                        .amount(payment.getAmount())
                        .currency(payment.getCurrency())
                        .status("PROCESSING")
                        .attemptCount(0)
                        .createdAt(LocalDateTime.now())
                        .build()));

        if ("SUCCEEDED".equals(refund.getStatus())) {
            payment.setStatus("REFUNDED");
            payment.setProcessedAt(LocalDateTime.now());
            paymentRepository.save(payment);
            return;
        }

        refund.setAttemptCount(refund.getAttemptCount() + 1);
        refund.setStatus("PROCESSING");
        refundRepository.save(refund);

        boolean refunded;
        try {
            PaymentGatewayService gateway = paymentGatewayFactory.getGateway(payment.getMethod());
            refunded = gateway.refundPayment(payment.getTransactionId());
        } catch (Exception e) {
            markRefundFailed(command, payment, refund, e.getMessage(), reason);
            return;
        }

        if (refunded) {
            refund.setStatus("SUCCEEDED");
            refund.setProviderRefundId(refundId);
            refund.setCompletedAt(LocalDateTime.now());
            refund.setFailureReason(null);
            refundRepository.save(refund);

            payment.setStatus("REFUNDED");
            payment.setProcessedAt(LocalDateTime.now());
            payment.setFailureReason(null);
            paymentRepository.save(payment);
            outboxEventService.record("PAYMENT", command.getPaymentId(), "PAYMENT_REFUNDED",
                    "payment-events", command.getProductId(), payload(command, "PAYMENT_REFUNDED", reason));
            return;
        }

        markRefundFailed(command, payment, refund, "PG refund returned false", reason);
    }

    @Override
    @Transactional
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

    private void markRefundFailed(ReservationWorkflowCommand command, PaymentRecord payment,
                                  RefundRecord refund, String failureReason, String reason) {
        refund.setStatus("FAILED");
        refund.setFailureReason(failureReason);
        refundRepository.save(refund);

        payment.setStatus("REFUND_FAILED");
        payment.setFailureReason(failureReason);
        payment.setProcessedAt(LocalDateTime.now());
        paymentRepository.save(payment);

        Map<String, Object> failedPayload = payload(command, "PAYMENT_REFUND_FAILED", reason);
        failedPayload.put("failureReason", failureReason);
        failedPayload.put("refundId", refund.getRefundId());
        outboxEventService.record("PAYMENT", command.getPaymentId(), "PAYMENT_REFUND_FAILED",
                "payment-events", command.getProductId(), failedPayload);
        throw new IllegalStateException("Payment refund failed: " + failureReason);
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
                "payment-events", command.getProductId(), payload(command, "PAYMENT_PROCESSED"));
    }

    @Override
    public ReservationWorkflowStepResult verifyPaymentStatus(ReservationWorkflowCommand command) {
        log.info("Verifying payment status: paymentId={}", command.getPaymentId());
        PaymentRecord payment = paymentRepository.findById(command.getPaymentId()).orElse(null);
        try {
            PaymentGatewayService gateway = paymentGatewayFactory.getGateway(command.getPaymentMethod());
            PaymentGatewayResult gatewayResult = command.getTossPaymentKey() != null && !command.getTossPaymentKey().isBlank()
                    ? gateway.getPaymentStatus(command.getTossPaymentKey())
                    : gateway.getPaymentStatusByPaymentId(command.getTossOrderId() != null ? command.getTossOrderId() : command.getPaymentId());
            
            if (gatewayResult.isSuccess()) {
                log.info("Payment was verified as APPROVED on gateway: paymentId={}, transactionId={}",
                        command.getPaymentId(), gatewayResult.getTransactionId());
                PaymentRecord verified = payment != null ? payment : paymentRepository.save(PaymentRecord.builder()
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
                verified.setStatus("APPROVED");
                verified.setTransactionId(gatewayResult.getTransactionId());
                verified.setApprovalNumber(gatewayResult.getApprovalNumber());
                verified.setGatewayName(gatewayResult.getGatewayName());
                verified.setProcessedAt(LocalDateTime.now());
                verified.setFailureReason(null);
                paymentRepository.save(verified);
                ensurePaymentSideEffects(command, verified);
                return paymentResult(verified, true, "verified as approved");
            } else {
                log.info("Payment was verified as NOT_APPROVED on gateway: paymentId={}, code={}",
                        command.getPaymentId(), gatewayResult.getErrorCode());
                if (payment != null) {
                    payment.setStatus("UNKNOWN");
                    payment.setFailureReason("Provider status not approved: " + gatewayResult.getErrorMessage());
                    payment.setProcessedAt(LocalDateTime.now());
                    paymentRepository.save(payment);
                    return paymentResult(payment, false, "provider status not approved: " + gatewayResult.getErrorMessage());
                }
                return ReservationWorkflowStepResult.failure("결제 승인 이력 없음: " + gatewayResult.getErrorMessage());
            }
        } catch (Exception e) {
            log.error("Error verifying payment status for paymentId={}", command.getPaymentId(), e);
            if (payment != null) {
                payment.setStatus("UNKNOWN");
                payment.setFailureReason("Provider status verification failed: " + e.getMessage());
                payment.setProcessedAt(LocalDateTime.now());
                paymentRepository.save(payment);
                return paymentResult(payment, false, "provider status verification failed: " + e.getMessage());
            }
            return ReservationWorkflowStepResult.failure("결제 상태 확인 실패: " + e.getMessage());
        }
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

package com.example.payment.application.temporal;

import com.example.payment.presentation.dto.request.CompleteReservationRequest;
import com.example.payment.presentation.dto.response.CompleteReservationResponse;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

public class CompleteReservationWorkflowImpl implements CompleteReservationWorkflow {

    private static final org.slf4j.Logger log = Workflow.getLogger(CompleteReservationWorkflowImpl.class);

    private final CompleteReservationActivities activities = Workflow.newActivityStub(
            CompleteReservationActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setMaximumAttempts(3)
                            .setInitialInterval(Duration.ofMillis(500))
                            .setBackoffCoefficient(2.0)
                            .build())
                    .build()
    );

    private CompleteReservationResponse result;
    private String status = "RUNNING";
    private String currentStep = "STARTED";
    private String compensationFailureReason;
    private ReservationWorkflowCommand currentCommand;

    @Override
    public CompleteReservationResponse process(CompleteReservationRequest request) {
        String workflowId = Workflow.getInfo().getWorkflowId();
        ReservationWorkflowCommand command = ReservationWorkflowCommand.from(
                workflowId,
                reservationId(request),
                orderId(request),
                deterministicId("PAY", request.getIdempotencyKey()),
                request
        );
        currentCommand = command;

        boolean reserved = false;
        boolean orderCreated = false;
        boolean paymentCompleted = false;

        try {
            currentStep = "RESERVE_INVENTORY";
            ReservationWorkflowStepResult reservation = activities.reserveInventory(command);
            if (!reservation.isSuccess()) {
                return fail(command, reservation.getMessage());
            }
            reserved = true;

            currentStep = "CREATE_ORDER";
            ReservationWorkflowStepResult order = activities.createOrder(command);
            if (!order.isSuccess()) {
                safeCompensate("cancelReservation", () -> activities.cancelReservation(command, "order creation failed"));
                return fail(command, order.getMessage());
            }
            orderCreated = true;

            currentStep = "PROCESS_PAYMENT";
            ReservationWorkflowStepResult payment = activities.processPayment(command);
            if (!payment.isSuccess()) {
                // PG 검증 실행 (타임아웃 및 네트워크 오류 복구)
                try {
                    ReservationWorkflowStepResult verified = activities.verifyPaymentStatus(command);
                    if (verified.isSuccess()) {
                        log.info("SAGA: Payment was approved on PG despite initial failure. Continuing workflow.");
                        paymentCompleted = true;
                    } else {
                        safeCompensate("cancelOrder", () -> activities.cancelOrder(command, "payment failed"));
                        safeCompensate("cancelReservation", () -> activities.cancelReservation(command, "payment failed"));
                        return fail(command, payment.getMessage());
                    }
                } catch (Exception verifyEx) {
                    safeCompensate("cancelOrder", () -> activities.cancelOrder(command, "payment failed"));
                    safeCompensate("cancelReservation", () -> activities.cancelReservation(command, "payment failed"));
                    return fail(command, payment.getMessage());
                }
            } else {
                paymentCompleted = true;
            }

            currentStep = "CONFIRM_INVENTORY";
            ReservationWorkflowStepResult inventoryConfirm = activities.confirmInventory(command);
            if (!inventoryConfirm.isSuccess()) {
                compensateApprovedPayment(command, "inventory confirmation failed");
                return fail(command, inventoryConfirm.getMessage());
            }

            currentStep = "MARK_ORDER_PAID";
            ReservationWorkflowStepResult paid = activities.markOrderPaid(command);
            if (!paid.isSuccess()) {
                compensateApprovedPayment(command, "order payment update failed");
                return fail(command, paid.getMessage());
            }

            currentStep = "COMPLETED";
            result = activities.buildSuccessResponse(command);
            status = "SUCCESS";
            return result;

        } catch (Exception e) {
            // 결제 상태 모호한 경우 검증
            if (!paymentCompleted && orderCreated) {
                try {
                    ReservationWorkflowStepResult verified = activities.verifyPaymentStatus(command);
                    if (verified.isSuccess()) {
                        paymentCompleted = true;
                    }
                } catch (Exception ignored) {}
            }

            if (paymentCompleted) {
                compensateApprovedPayment(command, "workflow exception");
            } else {
                if (orderCreated) {
                    safeCompensate("cancelOrder", () -> activities.cancelOrder(command, "workflow exception"));
                }
                if (reserved) {
                    safeCompensate("cancelReservation", () -> activities.cancelReservation(command, "workflow exception"));
                }
            }
            return fail(command, e.getMessage());
        }
    }

    @Override
    public CompleteReservationResponse getResult() {
        return result;
    }

    @Override
    public String getStatus() {
        return compensationFailureReason == null
                ? status + ":" + currentStep
                : status + ":" + currentStep + ":COMPENSATION_FAILED:" + compensationFailureReason;
    }

    private CompleteReservationResponse fail(ReservationWorkflowCommand command, String message) {
        String finalMessage = compensationFailureReason == null
                ? message
                : message + " (compensation issue: " + compensationFailureReason + ")";
        result = activities.buildFailureResponse(command, finalMessage);
        status = compensationFailureReason == null ? "FAILED" : "COMPENSATION_PENDING";
        if (result != null) {
            result.setStatus(status);
        }
        return result;
    }

    private boolean compensateApprovedPayment(ReservationWorkflowCommand command, String reason) {
        try {
            RefundCompensationResult refund = activities.refundPayment(command, reason);
            if (refund == null || !refund.isSuccessful()) {
                String detail = refund == null
                        ? "refund activity returned no result"
                        : refund.getStatus() + ": " + refund.getMessage();
                recordCompensationFailure("refundPayment", detail);
                return false;
            }
        } catch (Exception exception) {
            recordCompensationFailure("refundPayment", exception.getMessage());
            return false;
        }
        safeCompensate("cancelOrder", () -> activities.cancelOrder(command, reason));
        safeCompensate("cancelReservation", () -> activities.cancelReservation(command, reason));
        return true;
    }

    private void recordCompensationFailure(String activityName, String reason) {
        compensationFailureReason = activityName + " failed: " + reason;
        try {
            activities.recordCompensationFailure(currentCommand, activityName, reason);
        } catch (Exception ignored) {
            compensationFailureReason = compensationFailureReason + " (failure event record failed)";
        }
    }

    private void safeCompensate(String activityName, CompensationAction action) {
        try {
            action.run();
        } catch (Exception e) {
            recordCompensationFailure(activityName, e.getMessage());
        }
    }

    private String deterministicId(String prefix, String idempotencyKey) {
        UUID uuid = UUID.nameUUIDFromBytes((prefix + ":" + idempotencyKey).getBytes(StandardCharsets.UTF_8));
        return prefix + "-" + uuid.toString();
    }

    private String orderId(CompleteReservationRequest request) {
        if (request.getPaymentInfo() != null
                && request.getPaymentInfo().getTossOrderId() != null
                && !request.getPaymentInfo().getTossOrderId().isBlank()) {
            return request.getPaymentInfo().getTossOrderId();
        }
        return deterministicId("ORD", request.getIdempotencyKey());
    }

    private String reservationId(CompleteReservationRequest request) {
        if (request.getInventoryReservationId() != null
                && !request.getInventoryReservationId().isBlank()) {
            return request.getInventoryReservationId();
        }
        return deterministicId("RES", request.getIdempotencyKey());
    }

    @FunctionalInterface
    private interface CompensationAction {
        void run();
    }
}

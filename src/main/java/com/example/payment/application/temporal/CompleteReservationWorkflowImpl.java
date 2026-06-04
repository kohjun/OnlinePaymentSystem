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
                deterministicId("RES", request.getIdempotencyKey()),
                deterministicId("ORD", request.getIdempotencyKey()),
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
                safeCompensate("cancelOrder", () -> activities.cancelOrder(command, "payment failed"));
                safeCompensate("cancelReservation", () -> activities.cancelReservation(command, "payment failed"));
                return fail(command, payment.getMessage());
            }
            paymentCompleted = true;

            currentStep = "CONFIRM_INVENTORY";
            ReservationWorkflowStepResult inventoryConfirm = activities.confirmInventory(command);
            if (!inventoryConfirm.isSuccess()) {
                safeCompensate("refundPayment", () -> activities.refundPayment(command, "inventory confirmation failed"));
                safeCompensate("cancelOrder", () -> activities.cancelOrder(command, "inventory confirmation failed"));
                safeCompensate("cancelReservation", () -> activities.cancelReservation(command, "inventory confirmation failed"));
                return fail(command, inventoryConfirm.getMessage());
            }

            currentStep = "MARK_ORDER_PAID";
            ReservationWorkflowStepResult paid = activities.markOrderPaid(command);
            if (!paid.isSuccess()) {
                safeCompensate("refundPayment", () -> activities.refundPayment(command, "order payment update failed"));
                safeCompensate("cancelReservation", () -> activities.cancelReservation(command, "order payment update failed"));
                return fail(command, paid.getMessage());
            }

            currentStep = "COMPLETED";
            result = activities.buildSuccessResponse(command);
            status = "SUCCESS";
            return result;

        } catch (Exception e) {
            if (paymentCompleted) {
                safeCompensate("refundPayment", () -> activities.refundPayment(command, "workflow exception"));
            }
            if (orderCreated) {
                safeCompensate("cancelOrder", () -> activities.cancelOrder(command, "workflow exception"));
            }
            if (reserved) {
                safeCompensate("cancelReservation", () -> activities.cancelReservation(command, "workflow exception"));
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
        status = "FAILED";
        return result;
    }

    private void safeCompensate(String activityName, CompensationAction action) {
        try {
            action.run();
        } catch (Exception e) {
            compensationFailureReason = activityName + " failed: " + e.getMessage();
            try {
                activities.recordCompensationFailure(currentCommand, activityName, e.getMessage());
            } catch (Exception ignored) {
                compensationFailureReason = compensationFailureReason + " (failure event record failed)";
            }
        }
    }

    private String deterministicId(String prefix, String idempotencyKey) {
        UUID uuid = UUID.nameUUIDFromBytes((prefix + ":" + idempotencyKey).getBytes(StandardCharsets.UTF_8));
        return prefix + "-" + uuid.toString();
    }

    @FunctionalInterface
    private interface CompensationAction {
        void run();
    }
}

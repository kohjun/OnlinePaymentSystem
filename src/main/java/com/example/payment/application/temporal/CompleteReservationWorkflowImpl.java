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
    // 수동 검토 단계의 재조회 간격. 15분 고정이면 하루 약 1,100개 이벤트가 쌓여
    // 50일 안팎에 Temporal 이력 상한(51,200)에 닿고 워크플로가 강제 종료된다.
    // 타이머 길이만 바꾸는 변경은 재생 결정성에 영향이 없어 실행 중인 워크플로도
    // 새 간격을 따른다. 명령 순서를 바꿀 때는 CompleteReservationWorkflowReplayTest가 막는다.
    private static final int[] MANUAL_REVIEW_DELAY_SECONDS = {900, 1800, 3600, 7200};
    private final CompleteReservationActivities activities = Workflow.newActivityStub(
            CompleteReservationActivities.class, ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3)
                            .setInitialInterval(Duration.ofMillis(500)).setBackoffCoefficient(2).build()).build());
    private CompleteReservationResponse result;
    private String phase = "STARTED";
    private ReservationWorkflowCommand command;
    private long waitingSince;
    private int polls;
    private int manualPolls;
    private int manualRetryAfterSeconds = MANUAL_REVIEW_DELAY_SECONDS[0];

    @Override
    public CompleteReservationResponse process(CompleteReservationRequest request) {
        String workflowId = Workflow.getInfo().getWorkflowId();
        command = ReservationWorkflowCommand.from(workflowId,
                text(request.getInventoryReservationId(), id("RES", workflowId)),
                text(request.getPaymentInfo().getTossOrderId(), id("ORD", workflowId)), id("PAY", workflowId), request);
        try {
            phase = "RESERVE_INVENTORY";
            var reserved = activities.reserveInventory(command);
            if (!reserved.isSuccess()) return failure(reserved.getMessage());
            phase = "CREATE_ORDER";
            var order = activities.createOrder(command);
            if (!order.isSuccess()) {
                cancel("order creation failed");
                return failure(order.getMessage());
            }
        } catch (Exception failure) {
            cancel("reservation/order creation unavailable");
            return failure("Reservation could not be created");
        }
        phase = "PROCESS_PAYMENT";
        ReservationWorkflowStepResult payment;
        try {
            payment = activities.processPayment(command);
        } catch (Exception failure) {
            payment = unknown();
        }
        payment = awaitPayment(payment);
        if (java.util.Set.of("PARTIALLY_REFUNDED", "REFUND_UNKNOWN", "REFUND_FAILED").contains(
                java.util.Objects.toString(payment.getStatus(), ""))) return compensate("Payment cancellation requires reconciliation");
        if (!payment.isSuccess()) {
            cancel("payment rejected");
            return failure(payment.getMessage());
        }

        try {
            phase = "CONFIRM_INVENTORY";
            var confirmed = activities.confirmInventory(command);
            if (!confirmed.isSuccess()) return compensate(confirmed.getMessage());
            phase = "MARK_ORDER_PAID";
            var paid = activities.markOrderPaid(command);
            if (!paid.isSuccess()) return compensate(paid.getMessage());
        } catch (Exception failure) {
            return compensate("Order completion requires compensation");
        }
        resetWait();
        while (true) {
            try {
                result = activities.buildSuccessResponse(command);
                phase = "COMPLETED";
                return result;
            } catch (Exception failure) {
                waitForRecovery("ORDER_RECONCILING");
            }
        }
    }

    private ReservationWorkflowStepResult awaitPayment(ReservationWorkflowStepResult payment) {
        resetWait();
        while (payment == null || (!payment.isSuccess() && !java.util.Set.of(
                "FAILED", "REFUNDED", "PARTIALLY_REFUNDED", "REFUND_UNKNOWN", "REFUND_FAILED").contains(
                java.util.Objects.toString(payment.getStatus(), "")))) {
            waitForRecovery("PAYMENT_RECONCILING");
            try {
                payment = activities.verifyPaymentStatus(command);
            } catch (Exception failure) {
                payment = unknown();
            }
        }
        return payment;
    }

    private CompleteReservationResponse compensate(String reason) {
        resetWait();
        boolean recorded = false;
        while (true) {
            try {
                RefundCompensationResult refund = activities.refundPayment(command, reason);
                if (refund != null && refund.isSuccessful()) break;
            } catch (Exception ignored) {
                // Activity transport or persistence failure is not proof of refund rejection.
            }
            if (!recorded) {
                try {
                    activities.recordCompensationFailure(command, "refundPayment", "Refund verification required");
                    recorded = true;
                } catch (Exception ignored) { }
            }
            waitForRecovery("REFUND_RECONCILING");
        }
        cancel(reason);
        return failure(reason);
    }

    private void cancel(String reason) {
        resetWait();
        while (true) {
            try {
                activities.cancelOrder(command, reason);
                activities.cancelReservation(command, reason);
                return;
            } catch (Exception failure) {
                waitForRecovery("COMPENSATION_RECONCILING");
            }
        }
    }

    private CompleteReservationResponse failure(String message) {
        result = CompleteReservationResponse.builder().workflowId(command.getWorkflowId())
                .correlationId(command.getCorrelationId()).status("FAILED").message(message).build();
        phase = "COMPLETED";
        return result;
    }

    private void resetWait() { waitingSince = Workflow.currentTimeMillis(); polls = 0; manualPolls = 0; }

    private void waitForRecovery(String recoveryPhase) {
        boolean manual = Workflow.currentTimeMillis() - waitingSince >= Duration.ofMinutes(30).toMillis();
        phase = manual ? "MANUAL_REVIEW" : recoveryPhase;
        long seconds;
        if (manual) {
            manualRetryAfterSeconds = MANUAL_REVIEW_DELAY_SECONDS[Math.min(manualPolls++, MANUAL_REVIEW_DELAY_SECONDS.length - 1)];
            seconds = manualRetryAfterSeconds;
        } else {
            seconds = new long[]{5, 15, 30, 60}[Math.min(polls++, 3)];
        }
        Workflow.sleep(Duration.ofSeconds(seconds));
    }

    @Override
    public CompleteReservationResponse getResult() {
        if (result != null) return result;
        return CompleteReservationResponse.builder().workflowId(Workflow.getInfo().getWorkflowId())
                .status("PENDING").phase(phase).retryAfterSeconds("MANUAL_REVIEW".equals(phase) ? manualRetryAfterSeconds : 5)
                .message("Transaction verification is in progress").build();
    }

    @Override
    public String getStatus() { return result == null ? "PENDING:" + phase : result.getStatus(); }

    private ReservationWorkflowStepResult unknown() {
        return ReservationWorkflowStepResult.builder().status("UNKNOWN").build();
    }
    private String id(String prefix, String scope) {
        return prefix + "-" + UUID.nameUUIDFromBytes((prefix + ":" + scope).getBytes(StandardCharsets.UTF_8));
    }
    private String text(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
}

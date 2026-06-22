package com.example.payment.application.temporal;

import com.example.payment.presentation.dto.request.CompleteReservationRequest;
import com.example.payment.presentation.dto.response.CompleteReservationResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompleteReservationWorkflowTest {

    private static final String TASK_QUEUE = "test-payment-reservation";

    @Test
    void process_completesSuccessfulWorkflow() {
        SuccessfulActivities activities = new SuccessfulActivities();

        CompleteReservationResponse response = runWorkflow(activities);

        assertEquals("SUCCESS", response.getStatus());
        assertEquals("complete-reservation:test-key", response.getWorkflowId());
    }

    @Test
    void process_paymentFailureCancelsOrderAndReservation() {
        ConfigurableActivities activities = new ConfigurableActivities(FailStep.PAYMENT);

        CompleteReservationResponse response = runWorkflow(activities);

        assertEquals("FAILED", response.getStatus());
        assertEquals(1, activities.cancelOrderCalls.get());
        assertEquals(1, activities.cancelReservationCalls.get());
        assertEquals(0, activities.refundPaymentCalls.get());
    }

    @Test
    void process_inventoryConfirmationFailureRefundsAndCancels() {
        ConfigurableActivities activities = new ConfigurableActivities(FailStep.CONFIRM_INVENTORY);

        CompleteReservationResponse response = runWorkflow(activities);

        assertEquals("FAILED", response.getStatus());
        assertEquals(1, activities.refundPaymentCalls.get());
        assertEquals(1, activities.cancelOrderCalls.get());
        assertEquals(1, activities.cancelReservationCalls.get());
    }

    private CompleteReservationResponse runWorkflow(CompleteReservationActivities activities) {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker(TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(CompleteReservationWorkflowImpl.class);
            worker.registerActivitiesImplementations(activities);
            environment.start();

            WorkflowClient client = environment.getWorkflowClient();
            CompleteReservationWorkflow workflow = client.newWorkflowStub(
                    CompleteReservationWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setTaskQueue(TASK_QUEUE)
                            .setWorkflowId("complete-reservation:test-key")
                            .build()
            );

            return workflow.process(request());
        }
    }

    private CompleteReservationRequest request() {
        return CompleteReservationRequest.builder()
                .idempotencyKey("test-key")
                .correlationId("COR-test")
                .productId("PROD-001")
                .customerId("CUS-001")
                .quantity(1)
                .clientId("test")
                .paymentInfo(CompleteReservationRequest.PaymentInfo.builder()
                        .amount(new BigDecimal("100.00"))
                        .currency("KRW")
                        .paymentMethod("CREDIT_CARD")
                        .build())
                .build();
    }

    static class SuccessfulActivities implements CompleteReservationActivities {
        @Override
        public ReservationWorkflowStepResult reserveInventory(ReservationWorkflowCommand command) {
            return ReservationWorkflowStepResult.success("reserved");
        }

        @Override
        public ReservationWorkflowStepResult createOrder(ReservationWorkflowCommand command) {
            return ReservationWorkflowStepResult.success("order");
        }

        @Override
        public ReservationWorkflowStepResult processPayment(ReservationWorkflowCommand command) {
            return ReservationWorkflowStepResult.success("payment");
        }

        @Override
        public ReservationWorkflowStepResult confirmInventory(ReservationWorkflowCommand command) {
            return ReservationWorkflowStepResult.success("confirmed");
        }

        @Override
        public ReservationWorkflowStepResult markOrderPaid(ReservationWorkflowCommand command) {
            return ReservationWorkflowStepResult.success("paid");
        }

        @Override
        public void cancelReservation(ReservationWorkflowCommand command, String reason) {
        }

        @Override
        public void cancelOrder(ReservationWorkflowCommand command, String reason) {
        }

        @Override
        public void refundPayment(ReservationWorkflowCommand command, String reason) {
        }

        @Override
        public void recordCompensationFailure(ReservationWorkflowCommand command, String activityName, String reason) {
        }

        @Override
        public CompleteReservationResponse buildSuccessResponse(ReservationWorkflowCommand command) {
            return CompleteReservationResponse.builder()
                    .workflowId(command.getWorkflowId())
                    .status("SUCCESS")
                    .build();
        }

        @Override
        public CompleteReservationResponse buildFailureResponse(ReservationWorkflowCommand command, String message) {
            return CompleteReservationResponse.builder()
                    .workflowId(command.getWorkflowId())
                    .status("FAILED")
                    .message(message)
                    .build();
        }

        @Override
        public ReservationWorkflowStepResult verifyPaymentStatus(ReservationWorkflowCommand command) {
            return ReservationWorkflowStepResult.success("verified");
        }
    }

    enum FailStep {
        PAYMENT,
        CONFIRM_INVENTORY
    }

    static class ConfigurableActivities extends SuccessfulActivities {
        private final FailStep failStep;
        private final AtomicInteger cancelReservationCalls = new AtomicInteger();
        private final AtomicInteger cancelOrderCalls = new AtomicInteger();
        private final AtomicInteger refundPaymentCalls = new AtomicInteger();

        ConfigurableActivities(FailStep failStep) {
            this.failStep = failStep;
        }

        @Override
        public ReservationWorkflowStepResult processPayment(ReservationWorkflowCommand command) {
            if (failStep == FailStep.PAYMENT) {
                return ReservationWorkflowStepResult.failure("payment failed");
            }
            return super.processPayment(command);
        }

        @Override
        public ReservationWorkflowStepResult confirmInventory(ReservationWorkflowCommand command) {
            if (failStep == FailStep.CONFIRM_INVENTORY) {
                return ReservationWorkflowStepResult.failure("inventory confirmation failed");
            }
            return super.confirmInventory(command);
        }

        @Override
        public void cancelReservation(ReservationWorkflowCommand command, String reason) {
            cancelReservationCalls.incrementAndGet();
        }

        @Override
        public void cancelOrder(ReservationWorkflowCommand command, String reason) {
            cancelOrderCalls.incrementAndGet();
        }

        @Override
        public void refundPayment(ReservationWorkflowCommand command, String reason) {
            refundPaymentCalls.incrementAndGet();
        }

        @Override
        public ReservationWorkflowStepResult verifyPaymentStatus(ReservationWorkflowCommand command) {
            if (failStep == FailStep.PAYMENT) {
                return ReservationWorkflowStepResult.failure("payment not approved on PG");
            }
            return super.verifyPaymentStatus(command);
        }
    }
}

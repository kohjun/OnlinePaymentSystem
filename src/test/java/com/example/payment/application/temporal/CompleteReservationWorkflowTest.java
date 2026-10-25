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

    @Test
    void process_refundFailurePreservesOrderAndInventoryForManualRecovery() {
        ConfigurableActivities activities = new ConfigurableActivities(FailStep.CONFIRM_INVENTORY) {
            @Override
            public RefundCompensationResult refundPayment(ReservationWorkflowCommand command, String reason) {
                refundPaymentCalls.incrementAndGet();
                return RefundCompensationResult.builder()
                        .successful(false)
                        .status("REFUND_UNKNOWN")
                        .message("provider timeout")
                        .build();
            }
        };

        CompleteReservationResponse response;
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker(TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(CompleteReservationWorkflowImpl.class);
            worker.registerActivitiesImplementations(activities);
            environment.start();
            var workflow = environment.getWorkflowClient().newWorkflowStub(CompleteReservationWorkflow.class,
                    WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).setWorkflowId("unknown-refund").build());
            WorkflowClient.start(workflow::process, request());
            environment.sleep(java.time.Duration.ofSeconds(25));
            response = workflow.getResult();
            assertEquals("REFUND_RECONCILING", response.getPhase());
            io.temporal.client.WorkflowStub.fromTyped(workflow).terminate("test completed");
        }
        assertEquals("PENDING", response.getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(activities.refundPaymentCalls.get() >= 1);
        assertEquals(0, activities.cancelOrderCalls.get());
        assertEquals(0, activities.cancelReservationCalls.get());
    }

    @Test
    void unknownApprovalQueriesUntilApprovedWithoutReauthorizationOrRelease() {
        AtomicInteger authorizations = new AtomicInteger();
        AtomicInteger queries = new AtomicInteger();
        ConfigurableActivities activities = new ConfigurableActivities(FailStep.PAYMENT) {
            @Override public ReservationWorkflowStepResult processPayment(ReservationWorkflowCommand command) {
                authorizations.incrementAndGet();
                return ReservationWorkflowStepResult.builder().status("UNKNOWN").build();
            }
            @Override public ReservationWorkflowStepResult verifyPaymentStatus(ReservationWorkflowCommand command) {
                if (queries.incrementAndGet() < 3) throw new IllegalStateException("lookup unavailable");
                return ReservationWorkflowStepResult.success("approved");
            }
        };
        assertEquals("SUCCESS", runWorkflow(activities).getStatus());
        assertEquals(1, authorizations.get());
        assertEquals(0, activities.cancelOrderCalls.get());
        assertEquals(0, activities.cancelReservationCalls.get());
    }

    @Test
    void unknownRefundResumesCompensationOnlyAfterConfirmation() {
        ConfigurableActivities activities = new ConfigurableActivities(FailStep.CONFIRM_INVENTORY) {
            @Override public RefundCompensationResult refundPayment(ReservationWorkflowCommand command, String reason) {
                int attempt = refundPaymentCalls.incrementAndGet();
                assertEquals(0, cancelReservationCalls.get());
                return RefundCompensationResult.builder().successful(attempt >= 3)
                        .status(attempt >= 3 ? "REFUND_SUCCEEDED" : "REFUND_UNKNOWN").build();
            }
        };
        assertEquals("FAILED", runWorkflow(activities).getStatus());
        assertEquals(3, activities.refundPaymentCalls.get());
        assertEquals(1, activities.cancelReservationCalls.get());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void prolongedUncertaintyRequiresReviewWithoutReleasingInventory(boolean refundPending) {
        AtomicInteger authorizations = new AtomicInteger();
        AtomicInteger observations = new AtomicInteger();
        ConfigurableActivities activities = new ConfigurableActivities(FailStep.CONFIRM_INVENTORY) {
            @Override public ReservationWorkflowStepResult processPayment(ReservationWorkflowCommand command) {
                authorizations.incrementAndGet();
                return refundPending ? ReservationWorkflowStepResult.success("approved")
                        : ReservationWorkflowStepResult.builder().status("UNKNOWN").build();
            }
            @Override public ReservationWorkflowStepResult verifyPaymentStatus(ReservationWorkflowCommand command) {
                observations.incrementAndGet();
                return ReservationWorkflowStepResult.builder().status("UNKNOWN").build();
            }
            @Override public RefundCompensationResult refundPayment(ReservationWorkflowCommand command, String reason) {
                observations.incrementAndGet();
                return RefundCompensationResult.builder().successful(false).status("REFUND_UNKNOWN").build();
            }
        };
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker(TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(CompleteReservationWorkflowImpl.class);
            worker.registerActivitiesImplementations(activities);
            environment.start();
            var workflow = environment.getWorkflowClient().newWorkflowStub(CompleteReservationWorkflow.class,
                    WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE)
                            .setWorkflowId("long-pending-" + refundPending).build());
            WorkflowClient.start(workflow::process, request());
            try {
                environment.sleep(java.time.Duration.ofMinutes(31));
                var pending = workflow.getResult();
                assertEquals("PENDING", pending.getStatus());
                assertEquals("MANUAL_REVIEW", pending.getPhase());
                assertEquals(900, pending.getRetryAfterSeconds());
                int previousObservations = observations.get();
                environment.sleep(java.time.Duration.ofMinutes(16));
                assertEquals("PENDING", workflow.getResult().getStatus());
                org.junit.jupiter.api.Assertions.assertTrue(observations.get() > previousObservations);
                assertEquals(1, authorizations.get());
                assertEquals(0, activities.cancelOrderCalls.get());
                assertEquals(0, activities.cancelReservationCalls.get());
            } finally {
                io.temporal.client.WorkflowStub.fromTyped(workflow).terminate("test completed");
            }
        }
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
        public RefundCompensationResult refundPayment(ReservationWorkflowCommand command, String reason) {
            return RefundCompensationResult.builder().successful(true).status("REFUND_SUCCEEDED").build();
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
        protected final AtomicInteger cancelReservationCalls = new AtomicInteger();
        protected final AtomicInteger cancelOrderCalls = new AtomicInteger();
        protected final AtomicInteger refundPaymentCalls = new AtomicInteger();

        ConfigurableActivities(FailStep failStep) {
            this.failStep = failStep;
        }

        @Override
        public ReservationWorkflowStepResult processPayment(ReservationWorkflowCommand command) {
            if (failStep == FailStep.PAYMENT) {
                return ReservationWorkflowStepResult.builder().status("FAILED").message("declined").build();
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
        public RefundCompensationResult refundPayment(ReservationWorkflowCommand command, String reason) {
            refundPaymentCalls.incrementAndGet();
            return RefundCompensationResult.builder().successful(true).status("REFUND_SUCCEEDED").build();
        }

        @Override
        public ReservationWorkflowStepResult verifyPaymentStatus(ReservationWorkflowCommand command) {
            if (failStep == FailStep.PAYMENT) {
                return ReservationWorkflowStepResult.builder().status("FAILED").message("declined").build();
            }
            return super.verifyPaymentStatus(command);
        }
    }
}

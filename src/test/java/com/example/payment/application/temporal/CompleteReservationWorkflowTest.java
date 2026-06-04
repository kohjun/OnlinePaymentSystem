package com.example.payment.application.temporal;

import com.example.payment.presentation.dto.request.CompleteReservationRequest;
import com.example.payment.presentation.dto.response.CompleteReservationResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompleteReservationWorkflowTest {

    private static final String TASK_QUEUE = "test-payment-reservation";

    @Test
    void process_completesSuccessfulWorkflow() {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker(TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(CompleteReservationWorkflowImpl.class);
            worker.registerActivitiesImplementations(new SuccessfulActivities());
            environment.start();

            WorkflowClient client = environment.getWorkflowClient();
            CompleteReservationWorkflow workflow = client.newWorkflowStub(
                    CompleteReservationWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setTaskQueue(TASK_QUEUE)
                            .setWorkflowId("complete-reservation:test-key")
                            .build()
            );

            CompleteReservationResponse response = workflow.process(request());

            assertEquals("SUCCESS", response.getStatus());
            assertEquals("complete-reservation:test-key", response.getWorkflowId());
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
    }
}

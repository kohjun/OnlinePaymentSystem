package com.example.payment.application.temporal;

import com.example.payment.presentation.dto.request.CompleteReservationRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 재생 호환성 검사에 쓰는 대표 실행 이력.
 *
 * 저장된 JSON은 배포되어 있던 워크플로 코드가 남긴 이력이다. 워크플로 코드를
 * 바꾸면 {@link CompleteReservationWorkflowReplayTest}가 이 이력을 새 코드로
 * 재생한다. 새 이력을 추가할 때만 GENERATE_TEMPORAL_HISTORY_FIXTURES=true로
 * {@link WorkflowHistoryFixtureGeneratorTest}를 실행하고, 기존 파일은 덮어쓰지 않는다.
 */
final class WorkflowHistoryFixtures {

    static final Path DIRECTORY = Path.of("src", "test", "resources", "temporal-histories");
    private static final String TASK_QUEUE = "history-fixtures";

    private WorkflowHistoryFixtures() {
    }

    static Map<String, String> generate() {
        Map<String, String> histories = new LinkedHashMap<>();
        histories.put("success", run(new CompleteReservationWorkflowTest.SuccessfulActivities(), null));
        histories.put("payment-declined", run(
                new CompleteReservationWorkflowTest.ConfigurableActivities(CompleteReservationWorkflowTest.FailStep.PAYMENT), null));
        histories.put("inventory-failure-refunded", run(
                new CompleteReservationWorkflowTest.ConfigurableActivities(CompleteReservationWorkflowTest.FailStep.CONFIRM_INVENTORY), null));
        histories.put("unknown-approval-recovered", run(new UnknownThenApproved(3), null));
        // 배포 시점에 수동 검토 대기 중인 실행. 완료되지 않은 채로 저장한다.
        histories.put("manual-review-in-flight", run(new UnknownThenApproved(Integer.MAX_VALUE), Duration.ofMinutes(80)));
        histories.put("manual-review-recovered", run(new UnknownUntil(Duration.ofMinutes(75)), null));
        return histories;
    }

    static void write(Map<String, String> histories) throws Exception {
        Files.createDirectories(DIRECTORY);
        for (Map.Entry<String, String> history : histories.entrySet()) {
            Path file = DIRECTORY.resolve(history.getKey() + ".json");
            if (!Files.exists(file)) {
                Files.writeString(file, history.getValue(), StandardCharsets.UTF_8);
            }
        }
    }

    private static String run(CompleteReservationActivities activities, Duration leaveRunningAfter) {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker(TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(CompleteReservationWorkflowImpl.class);
            worker.registerActivitiesImplementations(activities);
            environment.start();
            String workflowId = "history-fixture-" + java.util.UUID.randomUUID();
            CompleteReservationWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
                    CompleteReservationWorkflow.class,
                    WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).setWorkflowId(workflowId).build());
            if (leaveRunningAfter == null) {
                workflow.process(request());
            } else {
                WorkflowClient.start(workflow::process, request());
                environment.sleep(leaveRunningAfter);
            }
            String json = environment.getWorkflowClient().fetchHistory(workflowId).toJson(true);
            if (leaveRunningAfter != null) {
                WorkflowStub.fromTyped(workflow).terminate("history captured");
            }
            return json;
        }
    }

    static CompleteReservationRequest request() {
        return CompleteReservationRequest.builder()
                .idempotencyKey("fixture-key")
                .correlationId("COR-fixture")
                .productId("PROD-001")
                .customerId("CUS-001")
                .quantity(1)
                .clientId("fixture")
                .paymentInfo(CompleteReservationRequest.PaymentInfo.builder()
                        .amount(new BigDecimal("100.00"))
                        .currency("KRW")
                        .paymentMethod("CREDIT_CARD")
                        .build())
                .build();
    }

    /** 승인 결과가 불명확하다가 n번째 상태 조회에서 승인으로 확인된다. */
    static class UnknownThenApproved extends CompleteReservationWorkflowTest.SuccessfulActivities {
        private final int approvedOnQuery;
        private final AtomicInteger queries = new AtomicInteger();

        UnknownThenApproved(int approvedOnQuery) {
            this.approvedOnQuery = approvedOnQuery;
        }

        @Override
        public ReservationWorkflowStepResult processPayment(ReservationWorkflowCommand command) {
            return ReservationWorkflowStepResult.builder().status("UNKNOWN").build();
        }

        @Override
        public ReservationWorkflowStepResult verifyPaymentStatus(ReservationWorkflowCommand command) {
            return queries.incrementAndGet() >= approvedOnQuery
                    ? ReservationWorkflowStepResult.success("approved")
                    : ReservationWorkflowStepResult.builder().status("UNKNOWN").build();
        }
    }

    /** 워크플로 시각으로 일정 시간이 지날 때까지 승인 결과가 불명확하다. */
    static class UnknownUntil extends CompleteReservationWorkflowTest.SuccessfulActivities {
        private final Duration unknownFor;
        private volatile long startedAt = -1;

        UnknownUntil(Duration unknownFor) {
            this.unknownFor = unknownFor;
        }

        @Override
        public ReservationWorkflowStepResult processPayment(ReservationWorkflowCommand command) {
            startedAt = io.temporal.activity.Activity.getExecutionContext().getInfo().getScheduledTimestamp();
            return ReservationWorkflowStepResult.builder().status("UNKNOWN").build();
        }

        @Override
        public ReservationWorkflowStepResult verifyPaymentStatus(ReservationWorkflowCommand command) {
            long now = io.temporal.activity.Activity.getExecutionContext().getInfo().getScheduledTimestamp();
            return now - startedAt >= unknownFor.toMillis()
                    ? ReservationWorkflowStepResult.success("approved")
                    : ReservationWorkflowStepResult.builder().status("UNKNOWN").build();
        }
    }
}

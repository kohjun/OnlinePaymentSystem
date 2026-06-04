package com.example.payment.application.service;

import com.example.payment.application.temporal.CompleteReservationWorkflow;
import com.example.payment.infrastructure.temporal.TemporalProperties;
import com.example.payment.presentation.dto.request.CompleteReservationRequest;
import com.example.payment.presentation.dto.response.CompleteReservationResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.temporal.enabled", havingValue = "true", matchIfMissing = true)
public class TemporalCompleteReservationGateway implements CompleteReservationGateway {

    private final WorkflowClient workflowClient;
    private final TemporalProperties temporalProperties;

    @Override
    public CompleteReservationResponse processCompleteReservation(CompleteReservationRequest request) {
        String workflowId = workflowId(request.getIdempotencyKey());
        CompleteReservationWorkflow workflow = workflowClient.newWorkflowStub(
                CompleteReservationWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(temporalProperties.getTaskQueue())
                        .setWorkflowId(workflowId)
                        .build()
        );

        try {
            WorkflowClient.start(workflow::process, request);
        } catch (WorkflowExecutionAlreadyStarted alreadyStarted) {
            log.info("Workflow already started: workflowId={}", workflowId);
        }

        try {
            return WorkflowStub.fromTyped(workflow).getResult(
                    temporalProperties.getResultWaitTimeoutSeconds(),
                    TimeUnit.SECONDS,
                    CompleteReservationResponse.class
            );
        } catch (TimeoutException e) {
            return pending(workflowId, request.getCorrelationId(), "workflow is still running");
        } catch (Exception e) {
            log.error("Failed to get workflow result: workflowId={}", workflowId, e);
            CompleteReservationResponse existing = getWorkflowStatus(workflowId);
            return existing != null ? existing : CompleteReservationResponse.builder()
                    .workflowId(workflowId)
                    .status("FAILED")
                    .message(e.getMessage())
                    .correlationId(request.getCorrelationId())
                    .build();
        }
    }

    @Override
    public CompleteReservationResponse getWorkflowStatus(String workflowId) {
        try {
            CompleteReservationWorkflow workflow = workflowClient.newWorkflowStub(
                    CompleteReservationWorkflow.class,
                    workflowId
            );
            CompleteReservationResponse result = workflow.getResult();
            if (result != null) {
                return result;
            }
            return pending(workflowId, null, workflow.getStatus());
        } catch (Exception e) {
            log.warn("Workflow status lookup failed: workflowId={}", workflowId, e);
            return null;
        }
    }

    private CompleteReservationResponse pending(String workflowId, String correlationId, String message) {
        return CompleteReservationResponse.builder()
                .workflowId(workflowId)
                .status("PENDING")
                .message(message)
                .correlationId(correlationId)
                .build();
    }

    private String workflowId(String idempotencyKey) {
        UUID uuid = UUID.nameUUIDFromBytes(("complete-reservation:" + idempotencyKey).getBytes(StandardCharsets.UTF_8));
        return "complete-reservation-" + uuid;
    }
}

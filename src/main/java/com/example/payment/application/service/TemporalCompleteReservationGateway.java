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
    private final CompleteReservationIdempotencyService idempotencyService;

    @Override
    public CompleteReservationResponse processCompleteReservation(CompleteReservationRequest request) {
        String workflowId = workflowId(request.getIdempotencyKey());
        CompleteReservationIdempotencyService.IdempotencyDecision idempotency =
                idempotencyService.prepare(request, workflowId);
        if (idempotency.getReplayResponse() != null) {
            return idempotency.getReplayResponse();
        }

        CompleteReservationWorkflow workflow = workflowClient.newWorkflowStub(
                CompleteReservationWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(temporalProperties.getTaskQueue())
                        .setWorkflowId(workflowId)
                        .build()
        );

        try {
            if (idempotency.isStartWorkflow()) {
                WorkflowClient.start(workflow::process, request);
            }
        } catch (WorkflowExecutionAlreadyStarted alreadyStarted) {
            log.info("Workflow already started: workflowId={}", workflowId);
        }

        try {
            CompleteReservationResponse response = WorkflowStub.fromTyped(workflow).getResult(
                    temporalProperties.getResultWaitTimeoutSeconds(),
                    TimeUnit.SECONDS,
                    CompleteReservationResponse.class
            );
            idempotencyService.recordResponse(request, response);
            return response;
        } catch (TimeoutException e) {
            CompleteReservationResponse response = pending(workflowId, request.getCorrelationId(), "workflow is still running");
            idempotencyService.recordResponse(request, response);
            return response;
        } catch (Exception e) {
            log.error("Failed to get workflow result: workflowId={}", workflowId, e);
            CompleteReservationResponse existing = getWorkflowStatus(workflowId);
            CompleteReservationResponse response = existing != null ? existing : CompleteReservationResponse.builder()
                    .workflowId(workflowId)
                    .status("FAILED")
                    .message(e.getMessage())
                    .correlationId(request.getCorrelationId())
                    .build();
            idempotencyService.recordResponse(request, response);
            return response;
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

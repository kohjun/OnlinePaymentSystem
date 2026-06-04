package com.example.payment.application.temporal;

import com.example.payment.presentation.dto.request.CompleteReservationRequest;
import com.example.payment.presentation.dto.response.CompleteReservationResponse;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface CompleteReservationWorkflow {

    @WorkflowMethod
    CompleteReservationResponse process(CompleteReservationRequest request);

    @QueryMethod
    CompleteReservationResponse getResult();

    @QueryMethod
    String getStatus();
}

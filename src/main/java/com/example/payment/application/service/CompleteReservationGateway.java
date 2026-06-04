package com.example.payment.application.service;

import com.example.payment.presentation.dto.request.CompleteReservationRequest;
import com.example.payment.presentation.dto.response.CompleteReservationResponse;

public interface CompleteReservationGateway {
    CompleteReservationResponse processCompleteReservation(CompleteReservationRequest request);

    CompleteReservationResponse getWorkflowStatus(String workflowId);
}

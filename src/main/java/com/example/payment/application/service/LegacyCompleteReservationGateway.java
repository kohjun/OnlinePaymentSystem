package com.example.payment.application.service;

import com.example.payment.presentation.dto.request.CompleteReservationRequest;
import com.example.payment.presentation.dto.response.CompleteReservationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.temporal.enabled", havingValue = "false")
public class LegacyCompleteReservationGateway implements CompleteReservationGateway {

    private final ReservationOrchestrator reservationOrchestrator;

    @Override
    public CompleteReservationResponse processCompleteReservation(CompleteReservationRequest request) {
        return reservationOrchestrator.processCompleteReservation(request);
    }

    @Override
    public CompleteReservationResponse getWorkflowStatus(String workflowId) {
        return CompleteReservationResponse.builder()
                .workflowId(workflowId)
                .status("UNAVAILABLE")
                .message("Temporal is disabled")
                .build();
    }
}

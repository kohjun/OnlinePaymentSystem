package com.example.payment.application.temporal;

import com.example.payment.presentation.dto.response.CompleteReservationResponse;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface CompleteReservationActivities {

    @ActivityMethod
    ReservationWorkflowStepResult reserveInventory(ReservationWorkflowCommand command);

    @ActivityMethod
    ReservationWorkflowStepResult createOrder(ReservationWorkflowCommand command);

    @ActivityMethod
    ReservationWorkflowStepResult processPayment(ReservationWorkflowCommand command);

    @ActivityMethod
    ReservationWorkflowStepResult confirmInventory(ReservationWorkflowCommand command);

    @ActivityMethod
    ReservationWorkflowStepResult markOrderPaid(ReservationWorkflowCommand command);

    @ActivityMethod
    void cancelReservation(ReservationWorkflowCommand command, String reason);

    @ActivityMethod
    void cancelOrder(ReservationWorkflowCommand command, String reason);

    @ActivityMethod
    void refundPayment(ReservationWorkflowCommand command, String reason);

    @ActivityMethod
    void recordCompensationFailure(ReservationWorkflowCommand command, String activityName, String reason);

    @ActivityMethod
    CompleteReservationResponse buildSuccessResponse(ReservationWorkflowCommand command);

    @ActivityMethod
    CompleteReservationResponse buildFailureResponse(ReservationWorkflowCommand command, String message);

    @ActivityMethod
    ReservationWorkflowStepResult verifyPaymentStatus(ReservationWorkflowCommand command);
}

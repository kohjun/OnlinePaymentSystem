package com.example.payment.presentation.dto.request;

import com.example.payment.domain.model.marketplace.FulfillmentStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateFulfillmentRequest {

    @NotNull
    private FulfillmentStatus fulfillmentStatus;

    @Size(max = 100)
    private String trackingCarrier;

    @Size(max = 100)
    private String trackingNumber;
}

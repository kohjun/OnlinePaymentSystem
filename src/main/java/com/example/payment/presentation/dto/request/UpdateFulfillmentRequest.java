package com.example.payment.presentation.dto.request;

import com.example.payment.domain.model.marketplace.FulfillmentStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateFulfillmentRequest {

    @NotNull
    private FulfillmentStatus fulfillmentStatus;
}

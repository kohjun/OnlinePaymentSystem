package com.example.payment.presentation.dto.request;

import com.example.payment.domain.model.marketplace.DisputeResolution;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ResolveMarketplaceDisputeRequest {
    @NotNull(message = "resolution is required")
    private DisputeResolution resolution;

    @Size(max = 1000)
    private String note;
}

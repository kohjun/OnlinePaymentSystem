package com.example.payment.presentation.dto.request;

import com.example.payment.domain.model.marketplace.ReviewStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ModerateMarketplaceReviewRequest {
    @NotNull(message = "status is required")
    private ReviewStatus status;

    @Size(max = 2000)
    private String note;
}

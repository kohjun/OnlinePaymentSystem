package com.example.payment.presentation.dto.request;

import com.example.payment.domain.model.marketplace.ReportStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ReviewMarketplaceReportRequest {
    @NotNull(message = "status is required")
    private ReportStatus status;

    @Size(max = 2000)
    private String note;
}

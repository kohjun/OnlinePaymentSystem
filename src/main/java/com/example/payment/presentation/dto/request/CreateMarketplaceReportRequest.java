package com.example.payment.presentation.dto.request;

import com.example.payment.domain.model.marketplace.ReportReason;
import com.example.payment.domain.model.marketplace.ReportTargetType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateMarketplaceReportRequest {
    @NotNull(message = "targetType is required")
    private ReportTargetType targetType;

    @NotBlank(message = "targetId is required")
    @Size(max = 100)
    private String targetId;

    @NotNull(message = "reason is required")
    private ReportReason reason;

    @Size(max = 2000)
    private String details;
}

package com.example.payment.presentation.dto.request;

import com.example.payment.domain.model.marketplace.DisputeResolution;
import com.example.payment.domain.model.marketplace.ReportStatus;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AdminOperationActionRequest {

    @Size(max = 2000)
    private String note;

    @Size(max = 200)
    private String idempotencyKey;

    private DisputeResolution resolution;

    private ReportStatus reportStatus;
}

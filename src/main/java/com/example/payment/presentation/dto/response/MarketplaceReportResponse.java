package com.example.payment.presentation.dto.response;

import com.example.payment.domain.model.marketplace.ReportReason;
import com.example.payment.domain.model.marketplace.ReportStatus;
import com.example.payment.domain.model.marketplace.ReportTargetType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class MarketplaceReportResponse {
    private String reportId;
    private String reporterUserId;
    private String reporterCustomerId;
    private ReportTargetType targetType;
    private String targetId;
    private ReportReason reason;
    private String details;
    private ReportStatus status;
    private String reviewedBy;
    private String reviewNote;
    private LocalDateTime resolvedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

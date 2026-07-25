package com.example.payment.application.service;

import com.example.payment.domain.model.marketplace.MarketplaceReport;
import com.example.payment.domain.model.marketplace.ReportStatus;
import com.example.payment.domain.model.marketplace.ReportTargetType;
import com.example.payment.domain.repository.MarketplaceListingRepository;
import com.example.payment.domain.repository.MarketplaceReportRepository;
import com.example.payment.domain.repository.SaleEventRepository;
import com.example.payment.domain.repository.SellerProfileRepository;
import com.example.payment.infrastructure.security.EverySalePrincipal;
import com.example.payment.presentation.dto.request.CreateMarketplaceReportRequest;
import com.example.payment.presentation.dto.request.ReviewMarketplaceReportRequest;
import com.example.payment.presentation.dto.response.MarketplaceReportResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MarketplaceReportService {

    private static final Set<ReportStatus> ACTIVE_REPORT_STATUSES = Set.of(
            ReportStatus.OPEN,
            ReportStatus.IN_REVIEW
    );

    private final MarketplaceReportRepository reportRepository;
    private final MarketplaceListingRepository listingRepository;
    private final SellerProfileRepository sellerProfileRepository;
    private final SaleEventRepository saleEventRepository;

    @Transactional
    public MarketplaceReportResponse createReport(EverySalePrincipal principal,
                                                  CreateMarketplaceReportRequest request) {
        String reporterUserId = requireText(principal.userId(), "Authenticated user id is required.");
        String reporterCustomerId = requireText(principal.customerId(), "Authenticated customer id is required.");
        String targetId = requireText(request.getTargetId(), "Report target id is required.");
        validateTargetExists(request.getTargetType(), targetId);

        return reportRepository.findFirstByReporterUserIdAndTargetTypeAndTargetIdAndReasonAndStatusInOrderByCreatedAtDesc(
                        reporterUserId,
                        request.getTargetType(),
                        targetId,
                        request.getReason(),
                        ACTIVE_REPORT_STATUSES
                )
                .map(this::toResponse)
                .orElseGet(() -> toResponse(reportRepository.save(MarketplaceReport.builder()
                        .reportId("RPT-" + shortId())
                        .reporterUserId(reporterUserId)
                        .reporterCustomerId(reporterCustomerId)
                        .targetType(request.getTargetType())
                        .targetId(targetId)
                        .reason(request.getReason())
                        .details(trimToNull(request.getDetails()))
                        .status(ReportStatus.OPEN)
                        .createdAt(LocalDateTime.now())
                        .build())));
    }

    @Transactional(readOnly = true)
    public List<MarketplaceReportResponse> getReportsByReporter(EverySalePrincipal principal) {
        String reporterUserId = requireText(principal.userId(), "Authenticated user id is required.");
        return reportRepository.findByReporterUserIdOrderByCreatedAtDesc(reporterUserId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MarketplaceReportResponse> getReportsForModeration(ReportStatus status) {
        ReportStatus reportStatus = status != null ? status : ReportStatus.OPEN;
        return reportRepository.findByStatusOrderByCreatedAtAsc(reportStatus)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public MarketplaceReportResponse reviewReport(String operatorId,
                                                  String reportId,
                                                  ReviewMarketplaceReportRequest request) {
        MarketplaceReport report = reportRepository.findById(reportId)
                .orElseThrow(() -> new IllegalArgumentException("Report not found: " + reportId));
        if (request.getStatus() == ReportStatus.OPEN) {
            throw new IllegalArgumentException("Report review status cannot be reset to OPEN: " + reportId);
        }
        report.setStatus(request.getStatus());
        report.setReviewedBy(requireText(operatorId, "Operator id is required."));
        report.setReviewNote(trimToNull(request.getNote()));
        if (request.getStatus() == ReportStatus.RESOLVED || request.getStatus() == ReportStatus.REJECTED) {
            report.setResolvedAt(LocalDateTime.now());
        }
        return toResponse(reportRepository.save(report));
    }

    private void validateTargetExists(ReportTargetType targetType, String targetId) {
        if (targetType == null) {
            throw new IllegalArgumentException("Report target type is required.");
        }
        switch (targetType) {
            case LISTING -> {
                if (!listingRepository.existsById(targetId)) {
                    throw new IllegalArgumentException("Listing report target not found: " + targetId);
                }
            }
            case SELLER -> {
                if (!sellerProfileRepository.existsById(targetId)) {
                    throw new IllegalArgumentException("Seller report target not found: " + targetId);
                }
            }
            case SALE_EVENT -> {
                if (!saleEventRepository.existsById(targetId)) {
                    throw new IllegalArgumentException("Sale event report target not found: " + targetId);
                }
            }
            case USER, ORDER -> {
                // User and order targets can originate outside the marketplace bounded context.
            }
        }
    }

    private MarketplaceReportResponse toResponse(MarketplaceReport report) {
        return MarketplaceReportResponse.builder()
                .reportId(report.getReportId())
                .reporterUserId(report.getReporterUserId())
                .reporterCustomerId(report.getReporterCustomerId())
                .targetType(report.getTargetType())
                .targetId(report.getTargetId())
                .reason(report.getReason())
                .details(report.getDetails())
                .status(report.getStatus())
                .reviewedBy(report.getReviewedBy())
                .reviewNote(report.getReviewNote())
                .resolvedAt(report.getResolvedAt())
                .createdAt(report.getCreatedAt())
                .updatedAt(report.getUpdatedAt())
                .build();
    }

    private String requireText(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }
}

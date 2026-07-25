package com.example.payment.application.service;

import com.example.payment.domain.model.marketplace.MarketplaceReport;
import com.example.payment.domain.model.marketplace.ReportReason;
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
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketplaceReportServiceTest {

    private final MarketplaceReportRepository reportRepository = mock(MarketplaceReportRepository.class);
    private final MarketplaceListingRepository listingRepository = mock(MarketplaceListingRepository.class);
    private final SellerProfileRepository sellerProfileRepository = mock(SellerProfileRepository.class);
    private final SaleEventRepository saleEventRepository = mock(SaleEventRepository.class);

    private final MarketplaceReportService service = new MarketplaceReportService(
            reportRepository,
            listingRepository,
            sellerProfileRepository,
            saleEventRepository
    );

    @Test
    void createsListingReportForAuthenticatedUser() {
        EverySalePrincipal principal = new EverySalePrincipal("USER-1", "CUST-1", null, Set.of("USER"));
        CreateMarketplaceReportRequest request = new CreateMarketplaceReportRequest();
        request.setTargetType(ReportTargetType.LISTING);
        request.setTargetId("LIST-1");
        request.setReason(ReportReason.COUNTERFEIT);
        request.setDetails("  Serial photo does not match the listing.  ");

        when(listingRepository.existsById("LIST-1")).thenReturn(true);
        when(reportRepository.findFirstByReporterUserIdAndTargetTypeAndTargetIdAndReasonAndStatusInOrderByCreatedAtDesc(
                eq("USER-1"),
                eq(ReportTargetType.LISTING),
                eq("LIST-1"),
                eq(ReportReason.COUNTERFEIT),
                anyCollection()
        )).thenReturn(Optional.empty());
        when(reportRepository.save(any(MarketplaceReport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MarketplaceReportResponse response = service.createReport(principal, request);

        assertEquals("USER-1", response.getReporterUserId());
        assertEquals("CUST-1", response.getReporterCustomerId());
        assertEquals(ReportTargetType.LISTING, response.getTargetType());
        assertEquals("LIST-1", response.getTargetId());
        assertEquals(ReportReason.COUNTERFEIT, response.getReason());
        assertEquals("Serial photo does not match the listing.", response.getDetails());
        assertEquals(ReportStatus.OPEN, response.getStatus());
        assertNotNull(response.getReportId());
    }

    @Test
    void returnsExistingActiveReportForDuplicateTargetAndReason() {
        EverySalePrincipal principal = new EverySalePrincipal("USER-1", "CUST-1", null, Set.of("USER"));
        MarketplaceReport existing = MarketplaceReport.builder()
                .reportId("RPT-1")
                .reporterUserId("USER-1")
                .reporterCustomerId("CUST-1")
                .targetType(ReportTargetType.SELLER)
                .targetId("SELLER-1")
                .reason(ReportReason.FRAUD)
                .status(ReportStatus.IN_REVIEW)
                .createdAt(LocalDateTime.now())
                .build();
        CreateMarketplaceReportRequest request = new CreateMarketplaceReportRequest();
        request.setTargetType(ReportTargetType.SELLER);
        request.setTargetId("SELLER-1");
        request.setReason(ReportReason.FRAUD);
        request.setDetails("New duplicate detail");

        when(sellerProfileRepository.existsById("SELLER-1")).thenReturn(true);
        when(reportRepository.findFirstByReporterUserIdAndTargetTypeAndTargetIdAndReasonAndStatusInOrderByCreatedAtDesc(
                eq("USER-1"),
                eq(ReportTargetType.SELLER),
                eq("SELLER-1"),
                eq(ReportReason.FRAUD),
                anyCollection()
        )).thenReturn(Optional.of(existing));

        MarketplaceReportResponse response = service.createReport(principal, request);

        assertEquals("RPT-1", response.getReportId());
        assertEquals(ReportStatus.IN_REVIEW, response.getStatus());
        verify(reportRepository, never()).save(any(MarketplaceReport.class));
    }

    @Test
    void reviewsReportAsOperatorAndMarksResolved() {
        MarketplaceReport report = MarketplaceReport.builder()
                .reportId("RPT-1")
                .reporterUserId("USER-1")
                .reporterCustomerId("CUST-1")
                .targetType(ReportTargetType.LISTING)
                .targetId("LIST-1")
                .reason(ReportReason.MISLEADING)
                .status(ReportStatus.OPEN)
                .createdAt(LocalDateTime.now())
                .build();
        ReviewMarketplaceReportRequest request = new ReviewMarketplaceReportRequest();
        request.setStatus(ReportStatus.RESOLVED);
        request.setNote("Listing was removed after verification.");

        when(reportRepository.findById("RPT-1")).thenReturn(Optional.of(report));
        when(reportRepository.save(any(MarketplaceReport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MarketplaceReportResponse response = service.reviewReport("ops-1", "RPT-1", request);

        assertEquals(ReportStatus.RESOLVED, response.getStatus());
        assertEquals("ops-1", response.getReviewedBy());
        assertEquals("Listing was removed after verification.", response.getReviewNote());
        assertNotNull(response.getResolvedAt());
    }

    @Test
    void rejectsReportWhenListingTargetDoesNotExist() {
        EverySalePrincipal principal = new EverySalePrincipal("USER-1", "CUST-1", null, Set.of("USER"));
        CreateMarketplaceReportRequest request = new CreateMarketplaceReportRequest();
        request.setTargetType(ReportTargetType.LISTING);
        request.setTargetId("LIST-MISSING");
        request.setReason(ReportReason.PROHIBITED_ITEM);

        when(listingRepository.existsById("LIST-MISSING")).thenReturn(false);

        assertThrows(IllegalArgumentException.class, () -> service.createReport(principal, request));
        verify(reportRepository, never()).save(any(MarketplaceReport.class));
    }
}

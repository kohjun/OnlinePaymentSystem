package com.example.payment.domain.repository;

import com.example.payment.domain.model.marketplace.MarketplaceReport;
import com.example.payment.domain.model.marketplace.ReportReason;
import com.example.payment.domain.model.marketplace.ReportStatus;
import com.example.payment.domain.model.marketplace.ReportTargetType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface MarketplaceReportRepository extends JpaRepository<MarketplaceReport, String> {
    List<MarketplaceReport> findByReporterUserIdOrderByCreatedAtDesc(String reporterUserId);

    List<MarketplaceReport> findByStatusOrderByCreatedAtAsc(ReportStatus status);

    long countByStatus(ReportStatus status);

    List<MarketplaceReport> findByStatusOrderByCreatedAtAsc(ReportStatus status, Pageable pageable);

    Optional<MarketplaceReport> findFirstByReporterUserIdAndTargetTypeAndTargetIdAndReasonAndStatusInOrderByCreatedAtDesc(
            String reporterUserId,
            ReportTargetType targetType,
            String targetId,
            ReportReason reason,
            Collection<ReportStatus> statuses
    );
}

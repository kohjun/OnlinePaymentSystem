package com.example.payment.presentation.controller;

import com.example.payment.application.service.MarketplaceReportService;
import com.example.payment.domain.model.marketplace.ReportStatus;
import com.example.payment.infrastructure.security.AuthorizationGuard;
import com.example.payment.infrastructure.security.EverySalePrincipal;
import com.example.payment.presentation.dto.request.CreateMarketplaceReportRequest;
import com.example.payment.presentation.dto.request.ReviewMarketplaceReportRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/c2c")
@RequiredArgsConstructor
@Validated
@Slf4j
public class C2CReportController {

    private final AuthorizationGuard authorizationGuard;
    private final MarketplaceReportService reportService;

    @PostMapping("/reports")
    public ResponseEntity<?> createReport(@Valid @RequestBody CreateMarketplaceReportRequest request) {
        try {
            EverySalePrincipal principal = authorizationGuard.currentPrincipal();
            return ResponseEntity.status(HttpStatus.CREATED).body(reportService.createReport(principal, request));
        } catch (IllegalArgumentException e) {
            log.warn("C2C report creation rejected: {}", e.getMessage());
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/reports")
    public ResponseEntity<?> getMyReports() {
        try {
            EverySalePrincipal principal = authorizationGuard.currentPrincipal();
            return ResponseEntity.ok(reportService.getReportsByReporter(principal));
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/moderation/reports")
    public ResponseEntity<?> getReportsForModeration(@RequestParam(required = false) String status) {
        try {
            authorizationGuard.requireAdmin();
            ReportStatus reportStatus = status == null || status.isBlank()
                    ? ReportStatus.OPEN
                    : ReportStatus.valueOf(status.trim().toUpperCase());
            return ResponseEntity.ok(reportService.getReportsForModeration(reportStatus));
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PatchMapping("/moderation/reports/{reportId}")
    public ResponseEntity<?> reviewReport(
            @PathVariable String reportId,
            @Valid @RequestBody ReviewMarketplaceReportRequest request) {
        try {
            authorizationGuard.requireAdmin();
            EverySalePrincipal principal = authorizationGuard.currentPrincipal();
            return ResponseEntity.ok(reportService.reviewReport(principal.userId(), reportId, request));
        } catch (IllegalArgumentException e) {
            log.warn("C2C report review rejected: {}", e.getMessage());
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "status", "FAILED",
                "message", message
        ));
    }
}

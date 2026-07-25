package com.example.payment.application.service;

import com.example.payment.domain.model.marketplace.DisputeResolution;
import com.example.payment.domain.model.marketplace.ReportStatus;
import com.example.payment.infrastructure.security.EverySalePrincipal;
import com.example.payment.infrastructure.security.SecurityAuditService;
import com.example.payment.presentation.dto.request.AdminOperationActionRequest;
import com.example.payment.presentation.dto.request.ReviewListingRequest;
import com.example.payment.presentation.dto.request.ReviewMarketplaceReportRequest;
import com.example.payment.presentation.dto.request.ReviewSellerPayoutAccountRequest;
import com.example.payment.presentation.dto.request.ReviewSellerVerificationRequest;
import com.example.payment.presentation.dto.response.AdminOperationActionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AdminOperationsActionService {

    private final SellerMarketplaceService sellerMarketplaceService;
    private final SellerPayoutAccountService sellerPayoutAccountService;
    private final MarketplaceReportService marketplaceReportService;
    private final MarketplaceOrderService marketplaceOrderService;
    private final PaymentProcessingService paymentProcessingService;
    private final SellerPayoutService sellerPayoutService;
    private final SecurityAuditService securityAuditService;

    public AdminOperationActionResponse execute(String queue,
                                                String itemId,
                                                String action,
                                                AdminOperationActionRequest request,
                                                EverySalePrincipal principal) {
        String normalizedQueue = requireText(queue, "Queue is required.");
        String normalizedItemId = requireText(itemId, "Operation item id is required.");
        String normalizedAction = requireText(action, "Operation action is required.");
        AdminOperationActionRequest safeRequest = request != null ? request : new AdminOperationActionRequest();
        String auditAction = "ADMIN_OPERATION_" + normalizedAction.toUpperCase(Locale.ROOT);

        try {
            Object result = switch (normalizedQueue) {
                case "listingReviews" -> reviewListing(normalizedItemId, normalizedAction, safeRequest, principal);
                case "sellerVerifications" -> reviewSellerVerification(normalizedItemId, normalizedAction, safeRequest, principal);
                case "payoutAccounts" -> reviewPayoutAccount(normalizedItemId, normalizedAction, safeRequest, principal);
                case "marketplaceReports" -> reviewReport(normalizedItemId, normalizedAction, safeRequest, principal);
                case "disputes", "partialRefundReviews" -> resolveDispute(normalizedItemId, normalizedAction, safeRequest, principal);
                case "marketplaceRefundFailures" -> resolveDispute(normalizedItemId, "buyerRefund", safeRequest, principal);
                case "paymentRefundFailures" -> retryPaymentRefund(normalizedItemId, normalizedQueue, normalizedAction, safeRequest);
                case "payoutRelease" -> releasePayout(normalizedItemId, normalizedAction);
                case "payoutRecovery" -> markPayoutRecovered(normalizedItemId, normalizedAction);
                default -> throw new IllegalArgumentException("Unsupported operations queue: " + normalizedQueue);
            };
            securityAuditService.recordGranted(auditAction, normalizedQueue, normalizedItemId);
            return AdminOperationActionResponse.builder()
                    .status("SUCCESS")
                    .action(normalizedAction)
                    .queue(normalizedQueue)
                    .resourceId(normalizedItemId)
                    .message("Operation action completed.")
                    .result(result)
                    .processedAt(LocalDateTime.now())
                    .build();
        } catch (RuntimeException e) {
            securityAuditService.record(auditAction, normalizedQueue, normalizedItemId, "FAILED", e.getMessage());
            throw e;
        }
    }

    private Object reviewListing(String listingId,
                                 String action,
                                 AdminOperationActionRequest request,
                                 EverySalePrincipal principal) {
        ReviewListingRequest review = new ReviewListingRequest();
        review.setOperatorId(operatorId(principal));
        review.setNote(defaultText(request.getNote(), "Admin operation queue action: " + action));
        return switch (action) {
            case "approve" -> sellerMarketplaceService.approveListing(listingId, review);
            case "reject" -> sellerMarketplaceService.rejectListing(listingId, review);
            default -> throw unsupported(action, "listingReviews");
        };
    }

    private Object reviewSellerVerification(String sellerId,
                                            String action,
                                            AdminOperationActionRequest request,
                                            EverySalePrincipal principal) {
        ReviewSellerVerificationRequest review = new ReviewSellerVerificationRequest();
        review.setApproved(switch (action) {
            case "approve" -> true;
            case "reject" -> false;
            default -> throw unsupported(action, "sellerVerifications");
        });
        review.setNote(defaultText(request.getNote(), "Admin seller verification " + action));
        return sellerMarketplaceService.reviewSellerVerification(sellerId, operatorId(principal), review);
    }

    private Object reviewPayoutAccount(String sellerId,
                                       String action,
                                       AdminOperationActionRequest request,
                                       EverySalePrincipal principal) {
        ReviewSellerPayoutAccountRequest review = new ReviewSellerPayoutAccountRequest();
        review.setApproved(switch (action) {
            case "approve" -> true;
            case "reject" -> false;
            default -> throw unsupported(action, "payoutAccounts");
        });
        review.setNote(defaultText(request.getNote(), "Admin payout account " + action));
        return sellerPayoutAccountService.review(sellerId, operatorId(principal), review);
    }

    private Object reviewReport(String reportId,
                                String action,
                                AdminOperationActionRequest request,
                                EverySalePrincipal principal) {
        ReviewMarketplaceReportRequest review = new ReviewMarketplaceReportRequest();
        ReportStatus status = switch (action) {
            case "startReview" -> ReportStatus.IN_REVIEW;
            case "resolve" -> request.getReportStatus() != null ? request.getReportStatus() : ReportStatus.RESOLVED;
            case "reject" -> ReportStatus.REJECTED;
            default -> throw unsupported(action, "marketplaceReports");
        };
        if (status == ReportStatus.OPEN) {
            throw new IllegalArgumentException("Report action cannot reset status to OPEN.");
        }
        review.setStatus(status);
        review.setNote(defaultText(request.getNote(), "Admin report " + action));
        return marketplaceReportService.reviewReport(operatorId(principal), reportId, review);
    }

    private Object resolveDispute(String marketplaceOrderId,
                                  String action,
                                  AdminOperationActionRequest request,
                                  EverySalePrincipal principal) {
        DisputeResolution resolution = switch (action) {
            case "payoutReady" -> DisputeResolution.PAYOUT_READY;
            case "payoutCancelled" -> DisputeResolution.PAYOUT_CANCELLED;
            case "buyerRefund", "retryRefund" -> DisputeResolution.BUYER_REFUND;
            case "resolve" -> request.getResolution() != null
                    ? request.getResolution()
                    : throwException("Dispute resolution is required for resolve action.");
            default -> throw unsupported(action, "disputes");
        };
        return marketplaceOrderService.resolveDispute(
                operatorId(principal),
                marketplaceOrderId,
                resolution,
                defaultText(request.getNote(), "Admin dispute action: " + action)
        );
    }

    private Object retryPaymentRefund(String paymentId,
                                      String queue,
                                      String action,
                                      AdminOperationActionRequest request) {
        if (!"retryRefund".equals(action)) {
            throw unsupported(action, queue);
        }
        PaymentProcessingService.RefundResult result = paymentProcessingService.refundPaymentWithResult(
                paymentId,
                defaultText(request.getIdempotencyKey(), "admin-retry-" + paymentId),
                defaultText(request.getNote(), "Admin refund retry from operations queue.")
        );
        if (!result.success()) {
            throw new IllegalArgumentException(result.code() + ": " + result.message());
        }
        return result.payment();
    }

    private Object markPayoutRecovered(String payoutId, String action) {
        if (!"markRecovered".equals(action)) {
            throw unsupported(action, "payoutRecovery");
        }
        return sellerPayoutService.markRecovered(payoutId);
    }

    private Object releasePayout(String payoutId, String action) {
        if (!"releasePayout".equals(action)) {
            throw unsupported(action, "payoutRelease");
        }
        return sellerPayoutService.releasePayout(payoutId);
    }

    private String operatorId(EverySalePrincipal principal) {
        if (principal != null && principal.userId() != null && !principal.userId().isBlank()) {
            return principal.userId().trim();
        }
        if (principal != null && principal.customerId() != null && !principal.customerId().isBlank()) {
            return principal.customerId().trim();
        }
        return "admin";
    }

    private String requireText(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private IllegalArgumentException unsupported(String action, String queue) {
        return new IllegalArgumentException("Unsupported action " + action + " for operations queue " + queue + ".");
    }

    private DisputeResolution throwException(String message) {
        throw new IllegalArgumentException(message);
    }
}

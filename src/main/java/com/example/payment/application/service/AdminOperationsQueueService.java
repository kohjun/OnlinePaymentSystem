package com.example.payment.application.service;

import com.example.payment.domain.entity.PaymentRecord;
import com.example.payment.domain.model.marketplace.ListingStatus;
import com.example.payment.domain.model.marketplace.MarketplaceListing;
import com.example.payment.domain.model.marketplace.MarketplaceOrder;
import com.example.payment.domain.model.marketplace.MarketplaceOrderStatus;
import com.example.payment.domain.model.marketplace.MarketplaceReport;
import com.example.payment.domain.model.marketplace.ReportStatus;
import com.example.payment.domain.model.marketplace.SellerPayout;
import com.example.payment.domain.model.marketplace.SellerPayoutAccount;
import com.example.payment.domain.model.marketplace.SellerPayoutAccountStatus;
import com.example.payment.domain.model.marketplace.SellerPayoutStatus;
import com.example.payment.domain.model.marketplace.SellerProfile;
import com.example.payment.domain.model.marketplace.SellerVerificationStatus;
import com.example.payment.domain.model.payment.PaymentStatus;
import com.example.payment.domain.repository.MarketplaceListingRepository;
import com.example.payment.domain.repository.MarketplaceOrderRepository;
import com.example.payment.domain.repository.MarketplaceReportRepository;
import com.example.payment.domain.repository.PaymentRecordRepository;
import com.example.payment.domain.repository.SellerPayoutAccountRepository;
import com.example.payment.domain.repository.SellerPayoutRepository;
import com.example.payment.domain.repository.SellerProfileRepository;
import com.example.payment.presentation.dto.response.AdminOperationsQueueResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class AdminOperationsQueueService {

    private final MarketplaceListingRepository listingRepository;
    private final SellerProfileRepository sellerProfileRepository;
    private final SellerPayoutAccountRepository payoutAccountRepository;
    private final MarketplaceReportRepository reportRepository;
    private final MarketplaceOrderRepository orderRepository;
    private final PaymentRecordRepository paymentRecordRepository;
    private final SellerPayoutRepository payoutRepository;

    @Transactional(readOnly = true)
    public AdminOperationsQueueResponse getQueues(Integer itemLimit) {
        int limit = normalizeLimit(itemLimit);
        Pageable pageable = PageRequest.of(0, limit);

        List<AdminOperationsQueueResponse.QueueSummary> queues = List.of(
                listingReviews(pageable),
                sellerVerificationReviews(pageable),
                payoutAccountReviews(pageable),
                marketplaceReportReviews(pageable, limit),
                openDisputes(pageable),
                paymentRefundFailures(pageable),
                marketplaceRefundFailures(pageable),
                partialRefundReviews(pageable),
                payoutRelease(pageable),
                payoutRecovery(pageable)
        );

        long totalOpen = queues.stream()
                .mapToLong(AdminOperationsQueueResponse.QueueSummary::getCount)
                .sum();

        return AdminOperationsQueueResponse.builder()
                .generatedAt(LocalDateTime.now())
                .totalOpen(totalOpen)
                .queues(queues)
                .build();
    }

    private AdminOperationsQueueResponse.QueueSummary listingReviews(Pageable pageable) {
        long count = listingRepository.countByStatus(ListingStatus.PENDING_REVIEW);
        List<AdminOperationsQueueResponse.QueueItem> items = listingRepository
                .findByStatusOrderByCreatedAtAsc(ListingStatus.PENDING_REVIEW, pageable)
                .stream()
                .map(this::listingItem)
                .toList();
        return queue("listingReviews", "판매글 검수", count, items);
    }

    private AdminOperationsQueueResponse.QueueSummary sellerVerificationReviews(Pageable pageable) {
        long count = sellerProfileRepository.countByVerificationStatus(SellerVerificationStatus.PENDING_REVIEW);
        List<AdminOperationsQueueResponse.QueueItem> items = sellerProfileRepository
                .findByVerificationStatusOrderByVerificationSubmittedAtAsc(SellerVerificationStatus.PENDING_REVIEW, pageable)
                .stream()
                .map(this::sellerVerificationItem)
                .toList();
        return queue("sellerVerifications", "판매자 인증 검수", count, items);
    }

    private AdminOperationsQueueResponse.QueueSummary payoutAccountReviews(Pageable pageable) {
        long count = payoutAccountRepository.countByStatus(SellerPayoutAccountStatus.PENDING_REVIEW);
        List<AdminOperationsQueueResponse.QueueItem> items = payoutAccountRepository
                .findByStatusOrderBySubmittedAtAsc(SellerPayoutAccountStatus.PENDING_REVIEW, pageable)
                .stream()
                .map(this::payoutAccountItem)
                .toList();
        return queue("payoutAccounts", "정산 계좌 검수", count, items);
    }

    private AdminOperationsQueueResponse.QueueSummary marketplaceReportReviews(Pageable pageable, int limit) {
        long count = reportRepository.countByStatus(ReportStatus.OPEN)
                + reportRepository.countByStatus(ReportStatus.IN_REVIEW);
        List<MarketplaceReport> openReports = reportRepository.findByStatusOrderByCreatedAtAsc(ReportStatus.OPEN, pageable);
        List<MarketplaceReport> inReviewReports = reportRepository.findByStatusOrderByCreatedAtAsc(ReportStatus.IN_REVIEW, pageable);
        List<AdminOperationsQueueResponse.QueueItem> items = Stream.concat(openReports.stream(), inReviewReports.stream())
                .sorted(Comparator.comparing(report -> defaultTime(report.getCreatedAt())))
                .limit(limit)
                .map(this::marketplaceReportItem)
                .toList();
        return queue("marketplaceReports", "신고 검토", count, items);
    }

    private AdminOperationsQueueResponse.QueueSummary openDisputes(Pageable pageable) {
        long count = orderRepository.countByDisputedAtIsNotNullAndDisputeResolvedAtIsNull();
        List<AdminOperationsQueueResponse.QueueItem> items = orderRepository
                .findByDisputedAtIsNotNullAndDisputeResolvedAtIsNullOrderByDisputedAtAsc(pageable)
                .stream()
                .map(order -> orderItem("MARKETPLACE_DISPUTE", order, "분쟁 주문"))
                .toList();
        return queue("disputes", "분쟁 처리", count, items);
    }

    private AdminOperationsQueueResponse.QueueSummary paymentRefundFailures(Pageable pageable) {
        long count = paymentRecordRepository.countByStatus(PaymentStatus.REFUND_FAILED.name());
        List<AdminOperationsQueueResponse.QueueItem> items = paymentRecordRepository
                .findByStatusOrderByCreatedAtAsc(PaymentStatus.REFUND_FAILED.name(), pageable)
                .stream()
                .map(this::paymentRefundFailureItem)
                .toList();
        return queue("paymentRefundFailures", "PG 환불 실패", count, items);
    }

    private AdminOperationsQueueResponse.QueueSummary marketplaceRefundFailures(Pageable pageable) {
        long count = orderRepository.countByStatus(MarketplaceOrderStatus.REFUND_FAILED);
        List<AdminOperationsQueueResponse.QueueItem> items = orderRepository
                .findByStatusOrderByUpdatedAtAsc(MarketplaceOrderStatus.REFUND_FAILED, pageable)
                .stream()
                .map(order -> orderItem("MARKETPLACE_REFUND_FAILURE", order, "환불 실패 주문"))
                .toList();
        return queue("marketplaceRefundFailures", "주문 환불 실패", count, items);
    }

    private AdminOperationsQueueResponse.QueueSummary partialRefundReviews(Pageable pageable) {
        long count = orderRepository.countByStatus(MarketplaceOrderStatus.PARTIALLY_REFUNDED);
        List<AdminOperationsQueueResponse.QueueItem> items = orderRepository
                .findByStatusOrderByUpdatedAtAsc(MarketplaceOrderStatus.PARTIALLY_REFUNDED, pageable)
                .stream()
                .map(order -> orderItem("MARKETPLACE_PARTIAL_REFUND", order, "부분 환불 주문"))
                .toList();
        return queue("partialRefundReviews", "부분 환불 검토", count, items);
    }

    private AdminOperationsQueueResponse.QueueSummary payoutRelease(Pageable pageable) {
        long count = payoutRepository.countByStatus(SellerPayoutStatus.READY_FOR_RELEASE);
        List<AdminOperationsQueueResponse.QueueItem> items = payoutRepository
                .findByStatusOrderByCreatedAtAsc(SellerPayoutStatus.READY_FOR_RELEASE, pageable)
                .stream()
                .map(this::payoutReleaseItem)
                .toList();
        return queue("payoutRelease", "정산 지급 대기", count, items);
    }

    private AdminOperationsQueueResponse.QueueSummary payoutRecovery(Pageable pageable) {
        long count = payoutRepository.countByStatus(SellerPayoutStatus.RECOVERY_REQUIRED);
        List<AdminOperationsQueueResponse.QueueItem> items = payoutRepository
                .findByStatusOrderByCreatedAtAsc(SellerPayoutStatus.RECOVERY_REQUIRED, pageable)
                .stream()
                .map(this::payoutRecoveryItem)
                .toList();
        return queue("payoutRecovery", "정산 회수 필요", count, items);
    }

    private AdminOperationsQueueResponse.QueueItem listingItem(MarketplaceListing listing) {
        return AdminOperationsQueueResponse.QueueItem.builder()
                .id(listing.getListingId())
                .type("MARKETPLACE_LISTING")
                .status(listing.getStatus().name())
                .title(listing.getTitle())
                .ownerId(listing.getSellerId())
                .createdAt(listing.getCreatedAt())
                .metadata(metadata(
                        "sellerId", listing.getSellerId(),
                        "productId", listing.getProductId(),
                        "itemCondition", listing.getItemCondition(),
                        "brand", listing.getBrand()
                ))
                .actions(actions(
                        action("approve", "승인", "success", false),
                        action("reject", "반려", "danger", true)
                ))
                .build();
    }

    private AdminOperationsQueueResponse.QueueItem sellerVerificationItem(SellerProfile seller) {
        return AdminOperationsQueueResponse.QueueItem.builder()
                .id(seller.getSellerId())
                .type("SELLER_VERIFICATION")
                .status(seller.getVerificationStatus().name())
                .title(seller.getDisplayName())
                .ownerId(firstNonBlank(seller.getOwnerUserId(), seller.getOwnerCustomerId()))
                .createdAt(firstTime(seller.getVerificationSubmittedAt(), seller.getCreatedAt()))
                .metadata(metadata(
                        "sellerId", seller.getSellerId(),
                        "ownerUserId", seller.getOwnerUserId(),
                        "ownerCustomerId", seller.getOwnerCustomerId(),
                        "evidenceRef", seller.getVerificationEvidenceRef(),
                        "note", seller.getVerificationNote()
                ))
                .actions(actions(
                        action("approve", "승인", "success", false),
                        action("reject", "반려", "danger", true)
                ))
                .build();
    }

    private AdminOperationsQueueResponse.QueueItem payoutAccountItem(SellerPayoutAccount account) {
        return AdminOperationsQueueResponse.QueueItem.builder()
                .id(account.getSellerId())
                .type("SELLER_PAYOUT_ACCOUNT")
                .status(account.getStatus().name())
                .title(account.getBankName() + " · " + account.getAccountHolderName())
                .ownerId(account.getSellerId())
                .createdAt(account.getSubmittedAt())
                .metadata(metadata(
                        "sellerId", account.getSellerId(),
                        "payoutAccountId", account.getPayoutAccountId(),
                        "bankCode", account.getBankCode(),
                        "accountLast4", account.getAccountLast4(),
                        "reviewNote", account.getReviewNote()
                ))
                .actions(actions(
                        action("approve", "승인", "success", false),
                        action("reject", "반려", "danger", true)
                ))
                .build();
    }

    private AdminOperationsQueueResponse.QueueItem marketplaceReportItem(MarketplaceReport report) {
        return AdminOperationsQueueResponse.QueueItem.builder()
                .id(report.getReportId())
                .type("MARKETPLACE_REPORT")
                .status(report.getStatus().name())
                .title(report.getTargetType().name() + " · " + report.getReason().name())
                .ownerId(report.getReporterUserId())
                .createdAt(report.getCreatedAt())
                .metadata(metadata(
                        "reporterCustomerId", report.getReporterCustomerId(),
                        "targetType", report.getTargetType().name(),
                        "targetId", report.getTargetId(),
                        "details", report.getDetails()
                ))
                .actions(actions(
                        action("startReview", "검토 시작", "neutral", false),
                        action("resolve", "해결", "success", true),
                        action("reject", "기각", "danger", true)
                ))
                .build();
    }

    private AdminOperationsQueueResponse.QueueItem orderItem(String type, MarketplaceOrder order, String titlePrefix) {
        return AdminOperationsQueueResponse.QueueItem.builder()
                .id(order.getMarketplaceOrderId())
                .type(type)
                .status(order.getStatus().name())
                .title(titlePrefix + " · " + order.getMarketplaceOrderId())
                .ownerId(order.getCustomerId())
                .amount(order.getAmount())
                .createdAt(firstTime(order.getDisputedAt(), order.getUpdatedAt(), order.getCreatedAt()))
                .metadata(metadata(
                        "sellerId", order.getSellerId(),
                        "listingId", order.getListingId(),
                        "saleEventId", order.getSaleEventId(),
                        "paymentId", order.getPaymentId(),
                        "fulfillmentStatus", order.getFulfillmentStatus().name(),
                        "disputeReason", order.getDisputeReason()
                ))
                .actions(orderActions(type))
                .build();
    }

    private AdminOperationsQueueResponse.QueueItem paymentRefundFailureItem(PaymentRecord payment) {
        return AdminOperationsQueueResponse.QueueItem.builder()
                .id(payment.getPaymentId())
                .type("PAYMENT_REFUND_FAILURE")
                .status(payment.getStatus())
                .title("환불 실패 결제 · " + payment.getPaymentId())
                .ownerId(payment.getCustomerId())
                .amount(payment.getAmount())
                .createdAt(firstTime(payment.getUpdatedAt(), payment.getCreatedAt()))
                .metadata(metadata(
                        "orderId", payment.getOrderId(),
                        "reservationId", payment.getReservationId(),
                        "gatewayName", payment.getGatewayName(),
                        "transactionId", payment.getTransactionId(),
                        "failureReason", payment.getFailureReason()
                ))
                .actions(actions(action("retryRefund", "환불 재시도", "danger", true)))
                .build();
    }

    private AdminOperationsQueueResponse.QueueItem payoutReleaseItem(SellerPayout payout) {
        return AdminOperationsQueueResponse.QueueItem.builder()
                .id(payout.getPayoutId())
                .type("SELLER_PAYOUT_RELEASE")
                .status(payout.getStatus().name())
                .title("정산 지급 대기 · " + payout.getPayoutId())
                .ownerId(payout.getSellerId())
                .amount(payout.getNetAmount())
                .createdAt(payout.getCreatedAt())
                .metadata(metadata(
                        "sellerId", payout.getSellerId(),
                        "sourceType", payout.getSourceType(),
                        "sourceId", payout.getSourceId(),
                        "grossAmount", payout.getGrossAmount(),
                        "platformFee", payout.getPlatformFee()
                ))
                .actions(actions(action("releasePayout", "정산 지급", "success", true)))
                .build();
    }

    private AdminOperationsQueueResponse.QueueItem payoutRecoveryItem(SellerPayout payout) {
        return AdminOperationsQueueResponse.QueueItem.builder()
                .id(payout.getPayoutId())
                .type("SELLER_PAYOUT_RECOVERY")
                .status(payout.getStatus().name())
                .title("정산 회수 필요 · " + payout.getPayoutId())
                .ownerId(payout.getSellerId())
                .amount(payout.getNetAmount())
                .createdAt(payout.getCreatedAt())
                .metadata(metadata(
                        "sellerId", payout.getSellerId(),
                        "sourceType", payout.getSourceType(),
                        "sourceId", payout.getSourceId(),
                        "grossAmount", payout.getGrossAmount(),
                        "platformFee", payout.getPlatformFee(),
                        "releasedAt", payout.getReleasedAt()
                ))
                .actions(actions(action("markRecovered", "회수 완료", "success", true)))
                .build();
    }

    private AdminOperationsQueueResponse.QueueSummary queue(String queue,
                                                            String label,
                                                            long count,
                                                            List<AdminOperationsQueueResponse.QueueItem> items) {
        return AdminOperationsQueueResponse.QueueSummary.builder()
                .queue(queue)
                .label(label)
                .count(count)
                .items(items)
                .build();
    }

    private int normalizeLimit(Integer itemLimit) {
        if (itemLimit == null) {
            return 5;
        }
        return Math.max(1, Math.min(itemLimit, 25));
    }

    private Map<String, Object> metadata(Object... entries) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            Object key = entries[i];
            Object value = entries[i + 1];
            if (key != null && value != null) {
                metadata.put(key.toString(), value);
            }
        }
        return metadata;
    }

    private List<AdminOperationsQueueResponse.QueueAction> orderActions(String type) {
        if ("MARKETPLACE_REFUND_FAILURE".equals(type)) {
            return actions(action("retryRefund", "환불 재시도", "danger", true));
        }
        if ("MARKETPLACE_PARTIAL_REFUND".equals(type)) {
            return actions(
                    action("payoutReady", "판매자 정산", "success", true),
                    action("payoutCancelled", "정산 취소", "danger", true),
                    action("buyerRefund", "추가 환불", "danger", true)
            );
        }
        return actions(
                action("payoutReady", "판매자 정산", "success", true),
                action("payoutCancelled", "정산 취소", "danger", true),
                action("buyerRefund", "구매자 환불", "danger", true)
        );
    }

    private List<AdminOperationsQueueResponse.QueueAction> actions(AdminOperationsQueueResponse.QueueAction... actions) {
        return List.of(actions);
    }

    private AdminOperationsQueueResponse.QueueAction action(String action, String label, String tone, boolean noteRequired) {
        return AdminOperationsQueueResponse.QueueAction.builder()
                .action(action)
                .label(label)
                .tone(tone)
                .noteRequired(noteRequired)
                .build();
    }

    private LocalDateTime firstTime(LocalDateTime... values) {
        for (LocalDateTime value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private LocalDateTime defaultTime(LocalDateTime value) {
        return value != null ? value : LocalDateTime.MAX;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}

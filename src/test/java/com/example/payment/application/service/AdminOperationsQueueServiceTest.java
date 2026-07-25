package com.example.payment.application.service;

import com.example.payment.domain.entity.PaymentRecord;
import com.example.payment.domain.model.marketplace.FulfillmentStatus;
import com.example.payment.domain.model.marketplace.ListingStatus;
import com.example.payment.domain.model.marketplace.MarketplaceCheckoutType;
import com.example.payment.domain.model.marketplace.MarketplaceListing;
import com.example.payment.domain.model.marketplace.MarketplaceOrder;
import com.example.payment.domain.model.marketplace.MarketplaceOrderStatus;
import com.example.payment.domain.model.marketplace.MarketplaceReport;
import com.example.payment.domain.model.marketplace.ReportReason;
import com.example.payment.domain.model.marketplace.ReportStatus;
import com.example.payment.domain.model.marketplace.ReportTargetType;
import com.example.payment.domain.model.marketplace.SaleType;
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
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminOperationsQueueServiceTest {

    private final MarketplaceListingRepository listingRepository = mock(MarketplaceListingRepository.class);
    private final SellerProfileRepository sellerProfileRepository = mock(SellerProfileRepository.class);
    private final SellerPayoutAccountRepository payoutAccountRepository = mock(SellerPayoutAccountRepository.class);
    private final MarketplaceReportRepository reportRepository = mock(MarketplaceReportRepository.class);
    private final MarketplaceOrderRepository orderRepository = mock(MarketplaceOrderRepository.class);
    private final PaymentRecordRepository paymentRecordRepository = mock(PaymentRecordRepository.class);
    private final SellerPayoutRepository payoutRepository = mock(SellerPayoutRepository.class);
    private final AdminOperationsQueueService service = new AdminOperationsQueueService(
            listingRepository,
            sellerProfileRepository,
            payoutAccountRepository,
            reportRepository,
            orderRepository,
            paymentRecordRepository,
            payoutRepository
    );

    @Test
    void returnsOpenOperationQueuesWithRepresentativeItems() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 9, 12, 0);

        when(listingRepository.countByStatus(ListingStatus.PENDING_REVIEW)).thenReturn(2L);
        when(listingRepository.findByStatusOrderByCreatedAtAsc(eq(ListingStatus.PENDING_REVIEW), any(Pageable.class)))
                .thenReturn(List.of(MarketplaceListing.builder()
                        .listingId("LST-1")
                        .sellerId("SELLER-1")
                        .productId("PROD-1")
                        .title("검수 대기 상품")
                        .status(ListingStatus.PENDING_REVIEW)
                        .createdAt(now.minusDays(3))
                        .build()));

        when(sellerProfileRepository.countByVerificationStatus(SellerVerificationStatus.PENDING_REVIEW)).thenReturn(1L);
        when(sellerProfileRepository.findByVerificationStatusOrderByVerificationSubmittedAtAsc(
                eq(SellerVerificationStatus.PENDING_REVIEW), any(Pageable.class)))
                .thenReturn(List.of(SellerProfile.builder()
                        .sellerId("SELLER-2")
                        .displayName("검증 대기 판매자")
                        .ownerUserId("USER-2")
                        .verificationStatus(SellerVerificationStatus.PENDING_REVIEW)
                        .verificationSubmittedAt(now.minusDays(2))
                        .build()));

        when(payoutAccountRepository.countByStatus(SellerPayoutAccountStatus.PENDING_REVIEW)).thenReturn(1L);
        when(payoutAccountRepository.findByStatusOrderBySubmittedAtAsc(
                eq(SellerPayoutAccountStatus.PENDING_REVIEW), any(Pageable.class)))
                .thenReturn(List.of(SellerPayoutAccount.builder()
                        .payoutAccountId("PACCT-1")
                        .sellerId("SELLER-1")
                        .bankName("Every Bank")
                        .bankCode("001")
                        .accountHolderName("Every Seller")
                        .accountLast4("1234")
                        .status(SellerPayoutAccountStatus.PENDING_REVIEW)
                        .submittedAt(now.minusDays(1))
                        .build()));

        when(reportRepository.countByStatus(ReportStatus.OPEN)).thenReturn(1L);
        when(reportRepository.countByStatus(ReportStatus.IN_REVIEW)).thenReturn(0L);
        when(reportRepository.findByStatusOrderByCreatedAtAsc(eq(ReportStatus.OPEN), any(Pageable.class)))
                .thenReturn(List.of(MarketplaceReport.builder()
                        .reportId("RPT-1")
                        .reporterUserId("USER-3")
                        .reporterCustomerId("CUST-3")
                        .targetType(ReportTargetType.LISTING)
                        .targetId("LST-1")
                        .reason(ReportReason.PROHIBITED_ITEM)
                        .status(ReportStatus.OPEN)
                        .createdAt(now.minusHours(8))
                        .build()));
        when(reportRepository.findByStatusOrderByCreatedAtAsc(eq(ReportStatus.IN_REVIEW), any(Pageable.class)))
                .thenReturn(List.of());

        MarketplaceOrder disputed = order("MORD-1", MarketplaceOrderStatus.PAID, now.minusHours(6));
        disputed.setDisputedAt(now.minusHours(5));
        disputed.setDisputeReason("배송 상태가 확인되지 않습니다.");
        when(orderRepository.countByDisputedAtIsNotNullAndDisputeResolvedAtIsNull()).thenReturn(1L);
        when(orderRepository.findByDisputedAtIsNotNullAndDisputeResolvedAtIsNullOrderByDisputedAtAsc(any(Pageable.class)))
                .thenReturn(List.of(disputed));

        when(paymentRecordRepository.countByStatus(PaymentStatus.REFUND_FAILED.name())).thenReturn(1L);
        when(paymentRecordRepository.findByStatusOrderByCreatedAtAsc(eq(PaymentStatus.REFUND_FAILED.name()), any(Pageable.class)))
                .thenReturn(List.of(PaymentRecord.builder()
                        .paymentId("PAY-1")
                        .orderId("ORD-1")
                        .reservationId("RES-1")
                        .customerId("CUST-1")
                        .amount(new BigDecimal("50000"))
                        .currency("KRW")
                        .method("CARD")
                        .status(PaymentStatus.REFUND_FAILED.name())
                        .gatewayName("TOSS_PAYMENTS")
                        .failureReason("Cancel request timed out.")
                        .createdAt(now.minusHours(4))
                        .build()));

        when(orderRepository.countByStatus(MarketplaceOrderStatus.REFUND_FAILED)).thenReturn(1L);
        when(orderRepository.findByStatusOrderByUpdatedAtAsc(eq(MarketplaceOrderStatus.REFUND_FAILED), any(Pageable.class)))
                .thenReturn(List.of(order("MORD-2", MarketplaceOrderStatus.REFUND_FAILED, now.minusHours(3))));

        when(orderRepository.countByStatus(MarketplaceOrderStatus.PARTIALLY_REFUNDED)).thenReturn(1L);
        when(orderRepository.findByStatusOrderByUpdatedAtAsc(eq(MarketplaceOrderStatus.PARTIALLY_REFUNDED), any(Pageable.class)))
                .thenReturn(List.of(order("MORD-3", MarketplaceOrderStatus.PARTIALLY_REFUNDED, now.minusHours(2))));

        when(payoutRepository.countByStatus(SellerPayoutStatus.READY_FOR_RELEASE)).thenReturn(2L);
        when(payoutRepository.findByStatusOrderByCreatedAtAsc(eq(SellerPayoutStatus.READY_FOR_RELEASE), any(Pageable.class)))
                .thenReturn(List.of(SellerPayout.builder()
                        .payoutId("PAYOUT-READY-1")
                        .sellerId("SELLER-1")
                        .sourceType("MARKETPLACE_ORDER")
                        .sourceId("MORD-1")
                        .grossAmount(new BigDecimal("50000"))
                        .platformFee(new BigDecimal("5000"))
                        .netAmount(new BigDecimal("45000"))
                        .status(SellerPayoutStatus.READY_FOR_RELEASE)
                        .createdAt(now.minusHours(2))
                        .build()));

        when(payoutRepository.countByStatus(SellerPayoutStatus.RECOVERY_REQUIRED)).thenReturn(1L);
        when(payoutRepository.findByStatusOrderByCreatedAtAsc(eq(SellerPayoutStatus.RECOVERY_REQUIRED), any(Pageable.class)))
                .thenReturn(List.of(SellerPayout.builder()
                        .payoutId("PAYOUT-1")
                        .sellerId("SELLER-1")
                        .sourceType("MARKETPLACE_ORDER")
                        .sourceId("MORD-3")
                        .grossAmount(new BigDecimal("50000"))
                        .platformFee(new BigDecimal("5000"))
                        .netAmount(new BigDecimal("45000"))
                        .status(SellerPayoutStatus.RECOVERY_REQUIRED)
                        .createdAt(now.minusHours(1))
                        .build()));

        AdminOperationsQueueResponse response = service.getQueues(3);

        assertNotNull(response.getGeneratedAt());
        assertEquals(12L, response.getTotalOpen());
        assertEquals(10, response.getQueues().size());
        assertEquals(2L, queue(response, "listingReviews").getCount());
        assertEquals("검수 대기 상품", queue(response, "listingReviews").getItems().get(0).getTitle());
        assertEquals("SELLER-1", queue(response, "payoutAccounts").getItems().get(0).getId());
        assertEquals("PACCT-1", queue(response, "payoutAccounts").getItems().get(0).getMetadata().get("payoutAccountId"));
        assertEquals("PAY-1", queue(response, "paymentRefundFailures").getItems().get(0).getId());
        assertEquals("PAYOUT-READY-1", queue(response, "payoutRelease").getItems().get(0).getId());
        assertEquals("releasePayout", queue(response, "payoutRelease").getItems().get(0).getActions().get(0).getAction());
        assertEquals(new BigDecimal("45000"), queue(response, "payoutRecovery").getItems().get(0).getAmount());
    }

    private MarketplaceOrder order(String id, MarketplaceOrderStatus status, LocalDateTime createdAt) {
        return MarketplaceOrder.builder()
                .marketplaceOrderId(id)
                .saleEventId("EVT-1")
                .listingId("LST-1")
                .sellerId("SELLER-1")
                .customerId("CUST-1")
                .saleType(SaleType.FIXED_PRICE)
                .checkoutType(MarketplaceCheckoutType.DIRECT)
                .status(status)
                .fulfillmentStatus(FulfillmentStatus.NOT_READY)
                .productId("PROD-1")
                .quantity(1)
                .amount(new BigDecimal("50000"))
                .currency("KRW")
                .paymentId("PAY-1")
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();
    }

    private AdminOperationsQueueResponse.QueueSummary queue(AdminOperationsQueueResponse response, String queue) {
        return response.getQueues().stream()
                .filter(summary -> queue.equals(summary.getQueue()))
                .findFirst()
                .orElseThrow();
    }
}

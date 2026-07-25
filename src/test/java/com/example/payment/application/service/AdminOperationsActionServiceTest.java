package com.example.payment.application.service;

import com.example.payment.domain.model.marketplace.DisputeResolution;
import com.example.payment.domain.model.marketplace.ListingStatus;
import com.example.payment.domain.model.marketplace.SellerPayoutStatus;
import com.example.payment.infrastructure.security.EverySalePrincipal;
import com.example.payment.infrastructure.security.SecurityAuditService;
import com.example.payment.presentation.dto.request.AdminOperationActionRequest;
import com.example.payment.presentation.dto.request.ReviewListingRequest;
import com.example.payment.presentation.dto.response.AdminOperationActionResponse;
import com.example.payment.presentation.dto.response.SellerListingResponse;
import com.example.payment.presentation.dto.response.SellerPayoutAccountResponse;
import com.example.payment.presentation.dto.response.SellerPayoutResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminOperationsActionServiceTest {

    private final SellerMarketplaceService sellerMarketplaceService = mock(SellerMarketplaceService.class);
    private final SellerPayoutAccountService sellerPayoutAccountService = mock(SellerPayoutAccountService.class);
    private final MarketplaceReportService marketplaceReportService = mock(MarketplaceReportService.class);
    private final MarketplaceOrderService marketplaceOrderService = mock(MarketplaceOrderService.class);
    private final PaymentProcessingService paymentProcessingService = mock(PaymentProcessingService.class);
    private final SellerPayoutService sellerPayoutService = mock(SellerPayoutService.class);
    private final SecurityAuditService securityAuditService = mock(SecurityAuditService.class);
    private final AdminOperationsActionService service = new AdminOperationsActionService(
            sellerMarketplaceService,
            sellerPayoutAccountService,
            marketplaceReportService,
            marketplaceOrderService,
            paymentProcessingService,
            sellerPayoutService,
            securityAuditService
    );

    @Test
    void approvesListingFromOperationsQueue() {
        when(sellerMarketplaceService.approveListing(eq("LIST-1"), any(ReviewListingRequest.class)))
                .thenReturn(SellerListingResponse.builder()
                        .listingId("LIST-1")
                        .status(ListingStatus.ACTIVE)
                        .build());

        AdminOperationActionRequest request = new AdminOperationActionRequest();
        request.setNote("검수 기준 충족");

        AdminOperationActionResponse response = service.execute(
                "listingReviews",
                "LIST-1",
                "approve",
                request,
                principal()
        );

        ArgumentCaptor<ReviewListingRequest> captor = ArgumentCaptor.forClass(ReviewListingRequest.class);
        verify(sellerMarketplaceService).approveListing(eq("LIST-1"), captor.capture());
        assertEquals("ops-admin", captor.getValue().getOperatorId());
        assertEquals("검수 기준 충족", captor.getValue().getNote());
        assertEquals("SUCCESS", response.getStatus());
        verify(securityAuditService).recordGranted("ADMIN_OPERATION_APPROVE", "listingReviews", "LIST-1");
    }

    @Test
    void approvesPayoutAccountUsingSellerIdFromOperationsQueue() {
        when(sellerPayoutAccountService.review(eq("SELLER-1"), eq("ops-admin"), any()))
                .thenReturn(SellerPayoutAccountResponse.builder()
                        .payoutAccountId("PACCT-1")
                        .sellerId("SELLER-1")
                        .build());

        AdminOperationActionResponse response = service.execute(
                "payoutAccounts",
                "SELLER-1",
                "approve",
                new AdminOperationActionRequest(),
                principal()
        );

        assertEquals("SUCCESS", response.getStatus());
        verify(sellerPayoutAccountService).review(eq("SELLER-1"), eq("ops-admin"), any());
    }

    @Test
    void resolvesMarketplaceRefundFailureThroughBuyerRefund() {
        AdminOperationActionRequest request = new AdminOperationActionRequest();
        request.setNote("환불 실패 재시도");

        service.execute(
                "marketplaceRefundFailures",
                "MORD-1",
                "retryRefund",
                request,
                principal()
        );

        verify(marketplaceOrderService).resolveDispute(
                "ops-admin",
                "MORD-1",
                DisputeResolution.BUYER_REFUND,
                "환불 실패 재시도"
        );
    }

    @Test
    void marksPayoutRecoveryAsRecovered() {
        when(sellerPayoutService.markRecovered("PAYOUT-1")).thenReturn(SellerPayoutResponse.builder()
                .payoutId("PAYOUT-1")
                .sellerId("SELLER-1")
                .netAmount(new BigDecimal("45000"))
                .status(SellerPayoutStatus.RECOVERED)
                .build());

        AdminOperationActionResponse response = service.execute(
                "payoutRecovery",
                "PAYOUT-1",
                "markRecovered",
                new AdminOperationActionRequest(),
                principal()
        );

        assertEquals("SUCCESS", response.getStatus());
        verify(sellerPayoutService).markRecovered("PAYOUT-1");
        verify(securityAuditService).recordGranted("ADMIN_OPERATION_MARKRECOVERED", "payoutRecovery", "PAYOUT-1");
    }

    @Test
    void releasesReadyPayoutFromOperationsQueue() {
        when(sellerPayoutService.releasePayout("PAYOUT-READY-1")).thenReturn(SellerPayoutResponse.builder()
                .payoutId("PAYOUT-READY-1")
                .sellerId("SELLER-1")
                .netAmount(new BigDecimal("45000"))
                .status(SellerPayoutStatus.RELEASED)
                .build());

        AdminOperationActionResponse response = service.execute(
                "payoutRelease",
                "PAYOUT-READY-1",
                "releasePayout",
                new AdminOperationActionRequest(),
                principal()
        );

        assertEquals("SUCCESS", response.getStatus());
        verify(sellerPayoutService).releasePayout("PAYOUT-READY-1");
        verify(securityAuditService).recordGranted("ADMIN_OPERATION_RELEASEPAYOUT", "payoutRelease", "PAYOUT-READY-1");
    }

    @Test
    void recordsAuditFailureForUnsupportedAction() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.execute(
                "listingReviews",
                "LIST-1",
                "publish",
                new AdminOperationActionRequest(),
                principal()
        ));

        assertEquals("Unsupported action publish for operations queue listingReviews.", ex.getMessage());
        verify(securityAuditService).record(
                "ADMIN_OPERATION_PUBLISH",
                "listingReviews",
                "LIST-1",
                "FAILED",
                "Unsupported action publish for operations queue listingReviews."
        );
    }

    private EverySalePrincipal principal() {
        return new EverySalePrincipal("ops-admin", "CUST-OPS", null, Set.of("ADMIN"));
    }
}

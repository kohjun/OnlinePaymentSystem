package com.example.payment.application.service;

import com.example.payment.domain.model.marketplace.MarketplaceOrder;
import com.example.payment.domain.model.marketplace.PayoutTransferStatus;
import com.example.payment.domain.model.marketplace.SellerPayout;
import com.example.payment.domain.model.marketplace.SellerPayoutStatus;
import com.example.payment.domain.model.marketplace.SellerPayoutTransfer;
import com.example.payment.domain.repository.MarketplaceOrderRepository;
import com.example.payment.domain.repository.SellerPayoutRepository;
import com.example.payment.domain.repository.SellerPayoutTransferRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Optional;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PayoutTransferClaimWriterTest {
    private final SellerPayoutRepository payouts = mock(SellerPayoutRepository.class);
    private final SellerPayoutTransferRepository transfers = mock(SellerPayoutTransferRepository.class);
    private final MarketplaceOrderRepository orders = mock(MarketplaceOrderRepository.class);
    private final CommerceSettlementGuard guard = mock(CommerceSettlementGuard.class);
    private final PayoutTransferClaimWriter writer = new PayoutTransferClaimWriter(payouts, transfers, orders, guard);
    private final MarketplaceOrder order = MarketplaceOrder.builder().marketplaceOrderId("ORDER-1").build();
    private final SellerPayout payout = SellerPayout.builder().payoutId("PAYOUT-1")
            .status(SellerPayoutStatus.READY_FOR_RELEASE).build();
    private final SellerPayoutTransfer transfer = SellerPayoutTransfer.builder().transferId("TRANSFER-1")
            .payoutId("PAYOUT-1").idempotencyKey("seller-payout:PAYOUT-1")
            .amount(new BigDecimal("90000.00")).currency("KRW")
            .status(PayoutTransferStatus.PROCESSING).build();

    @org.junit.jupiter.api.BeforeEach
    void stubLockedRecords() {
        when(payouts.findMarketplaceOrderId("PAYOUT-1")).thenReturn(Optional.of("ORDER-1"));
        when(orders.findByIdForUpdate("ORDER-1")).thenReturn(Optional.of(order));
        when(payouts.findByPayoutIdForUpdate("PAYOUT-1")).thenReturn(Optional.of(payout));
        when(transfers.findPayoutId("TRANSFER-1")).thenReturn(Optional.of("PAYOUT-1"));
        when(transfers.findById("TRANSFER-1")).thenReturn(Optional.of(transfer));
        when(transfers.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    }

    @ParameterizedTest
    @EnumSource(value = SellerPayoutStatus.class, names = {"CANCELLED", "DISPUTED"})
    void lateProviderSuccessRequiresRecoveryAfterRefundOrDispute(SellerPayoutStatus status) {
        payout.setStatus(status);
        var result = writer.observe("TRANSFER-1", verifiedReceipt());
        assertEquals(PayoutTransferStatus.SUCCEEDED, result.getStatus());
        assertEquals("PROVIDER-1", result.getProviderTransferId());
        assertEquals(SellerPayoutStatus.RECOVERY_REQUIRED, payout.getStatus());
        assertNotNull(payout.getReleasedAt());
        var locks = inOrder(orders, payouts);
        locks.verify(orders).findByIdForUpdate("ORDER-1");
        locks.verify(payouts).findByPayoutIdForUpdate("PAYOUT-1");
    }

    @Test
    void unresolvedRefundBlocksReleaseEvenBeforePayoutStatusIsUpdated() {
        doThrow(new IllegalStateException("Refund pending")).when(guard).requireClear(order);
        writer.observe("TRANSFER-1", verifiedReceipt());
        assertEquals(SellerPayoutStatus.RECOVERY_REQUIRED, payout.getStatus());
        assertEquals(PayoutTransferStatus.SUCCEEDED, transfer.getStatus());
    }

    @Test
    void lateUnknownObservationCannotUndoCompletedRecovery() {
        transfer.setStatus(PayoutTransferStatus.SUCCEEDED);
        transfer.setProviderTransferId("PROVIDER-1");
        payout.setStatus(SellerPayoutStatus.RECOVERED);
        writer.observe("TRANSFER-1", SellerPayoutTransferGateway.TransferResult.unknown("Lookup timeout"));
        assertEquals(SellerPayoutStatus.RECOVERED, payout.getStatus());
        assertEquals(PayoutTransferStatus.SUCCEEDED, transfer.getStatus());
        assertEquals("PROVIDER-1", transfer.getProviderTransferId());
        verify(payouts, never()).save(any());
        verify(transfers, never()).saveAndFlush(any());
    }

    @Test
    void repeatedClaimCannotAuthorizeAnotherOutgoingTransfer() {
        when(transfers.findByPayoutIdAndIdempotencyKey("PAYOUT-1", "seller-payout:PAYOUT-1"))
                .thenReturn(Optional.of(transfer));
        var result = writer.claim("PAYOUT-1", "TOSS_PAYOUTS");
        assertFalse(result.submit());
        assertSame(transfer, result.transfer());
        verify(transfers, never()).saveAndFlush(any());
    }

    private SellerPayoutTransferGateway.TransferResult verifiedReceipt() {
        return new SellerPayoutTransferGateway.TransferResult(PayoutTransferStatus.SUCCEEDED, "PROVIDER-1", null,
                "seller-payout:PAYOUT-1", new BigDecimal("90000"), "KRW");
    }

    @ParameterizedTest
    @EnumSource(value = PayoutTransferStatus.class, names = {"SUCCEEDED", "FAILED", "PROCESSING"})
    void unverifiedReceiptCannotFinalizeOrChangeProviderIdentity(PayoutTransferStatus status) {
        transfer.setProviderTransferId("PROVIDER-1");
        writer.observe("TRANSFER-1", new SellerPayoutTransferGateway.TransferResult(status, "OTHER", null,
                "seller-payout:PAYOUT-1", new BigDecimal("90000"), "KRW"));
        assertEquals(PayoutTransferStatus.UNKNOWN, transfer.getStatus());
        assertEquals("PROVIDER-1", transfer.getProviderTransferId());
        assertEquals(SellerPayoutStatus.READY_FOR_RELEASE, payout.getStatus());
        verify(payouts, never()).save(any());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "wrong,90000,KRW", "seller-payout:PAYOUT-1,89999,KRW", "seller-payout:PAYOUT-1,90000,USD"
    })
    void receiptMismatchRetainsLedgerAndLaterCorrectReceiptRecovers(String reference, BigDecimal amount, String currency) {
        writer.observe("TRANSFER-1", new SellerPayoutTransferGateway.TransferResult(
                PayoutTransferStatus.SUCCEEDED, "WRONG-RECEIPT", null, reference, amount, currency));
        assertEquals(PayoutTransferStatus.UNKNOWN, transfer.getStatus());
        assertNull(transfer.getProviderTransferId());
        assertEquals("PAYOUT_RESPONSE_MISMATCH", transfer.getFailureReason());
        assertEquals(SellerPayoutStatus.READY_FOR_RELEASE, payout.getStatus());
        assertNull(transfer.getCompletedAt());
        writer.observe("TRANSFER-1", verifiedReceipt());
        assertEquals(PayoutTransferStatus.SUCCEEDED, transfer.getStatus());
        assertEquals("PROVIDER-1", transfer.getProviderTransferId());
        assertEquals(SellerPayoutStatus.RELEASED, payout.getStatus());
    }

    @Test
    void successWithoutReceiptEvidenceRemainsUnknown() {
        writer.observe("TRANSFER-1", SellerPayoutTransferGateway.TransferResult.succeeded("PROVIDER-1"));
        assertEquals(PayoutTransferStatus.UNKNOWN, transfer.getStatus());
        assertNull(transfer.getProviderTransferId());
        verify(payouts, never()).save(any());
    }

    @Test
    void verifiedProviderRejectionDoesNotReleasePayout() {
        writer.observe("TRANSFER-1", new SellerPayoutTransferGateway.TransferResult(
                PayoutTransferStatus.FAILED, "PROVIDER-1", "REJECTED",
                "seller-payout:PAYOUT-1", new BigDecimal("90000"), "KRW"));
        assertEquals(PayoutTransferStatus.FAILED, transfer.getStatus());
        assertEquals("PROVIDER-1", transfer.getProviderTransferId());
        assertEquals("REJECTED", transfer.getFailureReason());
        assertEquals(SellerPayoutStatus.READY_FOR_RELEASE, payout.getStatus());
        verify(payouts, never()).save(any());
    }
}

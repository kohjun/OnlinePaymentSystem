package com.example.payment.application.service;

import com.example.payment.domain.model.marketplace.PayoutTransferStatus;
import com.example.payment.domain.model.marketplace.SellerPayout;
import com.example.payment.domain.model.marketplace.SellerPayoutAccount;
import com.example.payment.domain.model.marketplace.SellerPayoutAccountStatus;
import com.example.payment.domain.model.marketplace.SellerPayoutTransfer;
import com.example.payment.domain.repository.SellerPayoutAccountRepository;
import com.example.payment.domain.repository.SellerPayoutRepository;
import com.example.payment.domain.repository.SellerPayoutTransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SellerPayoutTransferCoordinatorTest {

    private final SellerPayoutTransferRepository transferRepository = mock(SellerPayoutTransferRepository.class);
    private final SellerPayoutAccountRepository accountRepository = mock(SellerPayoutAccountRepository.class);
    private final SellerPayoutRepository payoutRepository = mock(SellerPayoutRepository.class);
    private final SellerPayoutTransferGateway gateway = mock(SellerPayoutTransferGateway.class);
    private final ObjectProvider<SellerPayoutTransferGateway> gatewayProvider = mock(ObjectProvider.class);
    private final SellerPayoutTransferCoordinator coordinator = new SellerPayoutTransferCoordinator(
            transferRepository,
            accountRepository,
            payoutRepository,
            gatewayProvider
    );

    @BeforeEach
    void setUp() {
        when(gateway.providerName()).thenReturn("BANK_TEST");
    }

    @Test
    void successfulTransferIsPersistedWithStableIdempotencyKey() {
        stubVerifiedAccount();
        when(gatewayProvider.getIfAvailable()).thenReturn(gateway);
        when(gateway.providerName()).thenReturn("BANK_TEST");
        when(transferRepository.findByPayoutIdAndIdempotencyKey("PAYOUT-1", "seller-payout:PAYOUT-1"))
                .thenReturn(Optional.empty());
        when(transferRepository.save(any(SellerPayoutTransfer.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(gateway.transfer(any())).thenReturn(SellerPayoutTransferGateway.TransferResult.succeeded("BANK-TX-1"));

        SellerPayoutTransfer result = coordinator.transfer(payout());

        assertEquals(PayoutTransferStatus.SUCCEEDED, result.getStatus());
        assertEquals("BANK-TX-1", result.getProviderTransferId());
        assertEquals(1, result.getAttemptCount());
        verify(gateway).transfer(any(SellerPayoutTransferGateway.TransferRequest.class));
    }

    @Test
    void completedTransferReplayDoesNotCallProviderAgain() {
        stubVerifiedAccount();
        when(gatewayProvider.getIfAvailable()).thenReturn(gateway);
        SellerPayoutTransfer completed = transfer(PayoutTransferStatus.SUCCEEDED);
        when(transferRepository.findByPayoutIdAndIdempotencyKey("PAYOUT-1", "seller-payout:PAYOUT-1"))
                .thenReturn(Optional.of(completed));

        SellerPayoutTransfer result = coordinator.transfer(payout());

        assertEquals(PayoutTransferStatus.SUCCEEDED, result.getStatus());
        verify(gateway, never()).transfer(any());
        verify(gateway, never()).getStatus(any(), any());
    }

    @Test
    void timeoutMarksTransferUnknownAndDoesNotReleaseSilently() {
        stubVerifiedAccount();
        when(gatewayProvider.getIfAvailable()).thenReturn(gateway);
        when(gateway.providerName()).thenReturn("BANK_TEST");
        when(transferRepository.findByPayoutIdAndIdempotencyKey("PAYOUT-1", "seller-payout:PAYOUT-1"))
                .thenReturn(Optional.empty());
        when(transferRepository.save(any(SellerPayoutTransfer.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(gateway.transfer(any())).thenThrow(new IllegalStateException("timeout"));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> coordinator.transfer(payout()));

        assertEquals("Payout transfer result is unknown: PAYOUT-1", error.getMessage());
        verify(transferRepository, org.mockito.Mockito.atLeastOnce()).save(any(SellerPayoutTransfer.class));
    }

    @Test
    void unknownTransferUsesProviderStatusLookupInsteadOfSecondTransfer() {
        stubVerifiedAccount();
        when(gatewayProvider.getIfAvailable()).thenReturn(gateway);
        SellerPayoutTransfer unknown = transfer(PayoutTransferStatus.UNKNOWN);
        unknown.setProviderTransferId("BANK-TX-UNKNOWN");
        when(transferRepository.findByPayoutIdAndIdempotencyKey("PAYOUT-1", "seller-payout:PAYOUT-1"))
                .thenReturn(Optional.of(unknown));
        when(transferRepository.save(any(SellerPayoutTransfer.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(gateway.getStatus("BANK-TX-UNKNOWN", "seller-payout:PAYOUT-1"))
                .thenReturn(SellerPayoutTransferGateway.TransferResult.succeeded("BANK-TX-UNKNOWN"));

        SellerPayoutTransfer result = coordinator.transfer(payout());

        assertEquals(PayoutTransferStatus.SUCCEEDED, result.getStatus());
        verify(gateway, never()).transfer(any());
        verify(gateway).getStatus("BANK-TX-UNKNOWN", "seller-payout:PAYOUT-1");
    }

    @Test
    void reconciliationFinalizesUnknownTransferAndReleasesPayoutLedger() {
        when(gatewayProvider.getIfAvailable()).thenReturn(gateway);
        SellerPayoutTransfer unknown = transfer(PayoutTransferStatus.UNKNOWN);
        unknown.setProviderTransferId("BANK-TX-UNKNOWN");
        unknown.setUpdatedAt(LocalDateTime.now().minusMinutes(10));
        when(transferRepository.findByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                any(), any(), any(Pageable.class))).thenReturn(List.of(unknown));
        when(transferRepository.save(any(SellerPayoutTransfer.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(gateway.getStatus("BANK-TX-UNKNOWN", "seller-payout:PAYOUT-1"))
                .thenReturn(SellerPayoutTransferGateway.TransferResult.succeeded("BANK-TX-UNKNOWN"));
        SellerPayout payout = payout();
        payout.setStatus(com.example.payment.domain.model.marketplace.SellerPayoutStatus.READY_FOR_RELEASE);
        when(payoutRepository.findById("PAYOUT-1")).thenReturn(Optional.of(payout));
        when(payoutRepository.save(any(SellerPayout.class))).thenAnswer(invocation -> invocation.getArgument(0));

        int reconciled = coordinator.reconcileStaleTransfers(Duration.ofMinutes(5), 50);

        assertEquals(1, reconciled);
        assertEquals(PayoutTransferStatus.SUCCEEDED, unknown.getStatus());
        assertEquals(com.example.payment.domain.model.marketplace.SellerPayoutStatus.RELEASED, payout.getStatus());
        verify(gateway, never()).transfer(any());
    }

    @Test
    void providerSuccessWithoutTransferIdRemainsUnknown() {
        stubVerifiedAccount();
        when(gatewayProvider.getIfAvailable()).thenReturn(gateway);
        when(transferRepository.findByPayoutIdAndIdempotencyKey("PAYOUT-1", "seller-payout:PAYOUT-1"))
                .thenReturn(Optional.empty());
        when(transferRepository.save(any(SellerPayoutTransfer.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(gateway.transfer(any())).thenReturn(new SellerPayoutTransferGateway.TransferResult(
                PayoutTransferStatus.SUCCEEDED, null, null));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> coordinator.transfer(payout()));

        assertEquals("Payout transfer did not succeed: UNKNOWN", error.getMessage());
    }

    private void stubVerifiedAccount() {
        when(accountRepository.findBySellerId("SELLER-1")).thenReturn(Optional.of(SellerPayoutAccount.builder()
                .payoutAccountId("PACCT-1")
                .sellerId("SELLER-1")
                .accountRef("vault://account/1")
                .status(SellerPayoutAccountStatus.VERIFIED)
                .build()));
    }

    private SellerPayout payout() {
        return SellerPayout.builder()
                .payoutId("PAYOUT-1")
                .sellerId("SELLER-1")
                .netAmount(new BigDecimal("90000.00"))
                .build();
    }

    private SellerPayoutTransfer transfer(PayoutTransferStatus status) {
        return SellerPayoutTransfer.builder()
                .transferId("PTR-1")
                .payoutId("PAYOUT-1")
                .idempotencyKey("seller-payout:PAYOUT-1")
                .amount(new BigDecimal("90000.00"))
                .currency("KRW")
                .provider("BANK_TEST")
                .status(status)
                .attemptCount(1)
                .build();
    }
}

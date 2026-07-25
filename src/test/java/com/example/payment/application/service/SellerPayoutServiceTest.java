package com.example.payment.application.service;

import com.example.payment.domain.model.marketplace.SellerPayout;
import com.example.payment.domain.model.marketplace.SellerPayoutAccountStatus;
import com.example.payment.domain.model.marketplace.SellerPayoutStatus;
import com.example.payment.domain.repository.SellerPayoutAccountRepository;
import com.example.payment.domain.repository.SellerPayoutRepository;
import com.example.payment.presentation.dto.response.SellerPayoutResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SellerPayoutServiceTest {

    private final SellerPayoutRepository repository = mock(SellerPayoutRepository.class);
    private final SellerPayoutAccountRepository payoutAccountRepository = mock(SellerPayoutAccountRepository.class);
    private final SellerPayoutTransferCoordinator payoutTransferCoordinator = mock(SellerPayoutTransferCoordinator.class);
    private final SellerPayoutService service = new SellerPayoutService(
            repository,
            payoutAccountRepository,
            payoutTransferCoordinator
    );

    @Test
    void createsHeldPayoutWithPlatformFee() {
        when(repository.existsBySourceTypeAndSourceId("MARKETPLACE_ORDER", "MORD-1")).thenReturn(false);
        when(repository.save(any(SellerPayout.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.createHeldPayout("SELLER-1", "MARKETPLACE_ORDER", "MORD-1", new BigDecimal("100000"));

        ArgumentCaptor<SellerPayout> captor = ArgumentCaptor.forClass(SellerPayout.class);
        verify(repository).save(captor.capture());
        assertEquals(new BigDecimal("100000"), captor.getValue().getGrossAmount());
        assertEquals(new BigDecimal("10000.00"), captor.getValue().getPlatformFee());
        assertEquals(new BigDecimal("90000.00"), captor.getValue().getNetAmount());
        assertEquals(SellerPayoutStatus.HELD, captor.getValue().getStatus());
    }

    @Test
    void skipsDuplicatePayoutSource() {
        when(repository.existsBySourceTypeAndSourceId("MARKETPLACE_ORDER", "MORD-1")).thenReturn(true);

        service.createHeldPayout("SELLER-1", "MARKETPLACE_ORDER", "MORD-1", new BigDecimal("100000"));

        verify(repository, never()).save(any());
    }

    @Test
    void releasesReadyPayout() {
        SellerPayout payout = SellerPayout.builder()
                .payoutId("PAYOUT-1")
                .sellerId("SELLER-1")
                .sourceType("MARKETPLACE_ORDER")
                .sourceId("MORD-1")
                .grossAmount(new BigDecimal("100000"))
                .platformFee(new BigDecimal("10000.00"))
                .netAmount(new BigDecimal("90000.00"))
                .status(SellerPayoutStatus.READY_FOR_RELEASE)
                .createdAt(LocalDateTime.now())
                .build();
        when(repository.findByPayoutIdAndSellerId("PAYOUT-1", "SELLER-1")).thenReturn(Optional.of(payout));
        when(payoutAccountRepository.existsBySellerIdAndStatus("SELLER-1", SellerPayoutAccountStatus.VERIFIED)).thenReturn(true);
        when(repository.save(any(SellerPayout.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SellerPayoutResponse response = service.releasePayout("SELLER-1", "PAYOUT-1");

        assertEquals(SellerPayoutStatus.RELEASED, response.getStatus());
        verify(payoutTransferCoordinator).transfer(payout);
    }

    @Test
    void releasesReadyPayoutByPayoutIdForOperationsQueue() {
        SellerPayout payout = SellerPayout.builder()
                .payoutId("PAYOUT-1")
                .sellerId("SELLER-1")
                .sourceType("MARKETPLACE_ORDER")
                .sourceId("MORD-1")
                .grossAmount(new BigDecimal("100000"))
                .platformFee(new BigDecimal("10000.00"))
                .netAmount(new BigDecimal("90000.00"))
                .status(SellerPayoutStatus.READY_FOR_RELEASE)
                .createdAt(LocalDateTime.now())
                .build();
        when(repository.findById("PAYOUT-1")).thenReturn(Optional.of(payout));
        when(payoutAccountRepository.existsBySellerIdAndStatus("SELLER-1", SellerPayoutAccountStatus.VERIFIED)).thenReturn(true);
        when(repository.save(any(SellerPayout.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SellerPayoutResponse response = service.releasePayout("PAYOUT-1");

        assertEquals(SellerPayoutStatus.RELEASED, response.getStatus());
        verify(payoutTransferCoordinator).transfer(payout);
    }

    @Test
    void rejectsReleaseWhenSellerPayoutAccountIsNotVerified() {
        SellerPayout payout = SellerPayout.builder()
                .payoutId("PAYOUT-1")
                .sellerId("SELLER-1")
                .sourceType("MARKETPLACE_ORDER")
                .sourceId("MORD-1")
                .grossAmount(new BigDecimal("100000"))
                .platformFee(new BigDecimal("10000.00"))
                .netAmount(new BigDecimal("90000.00"))
                .status(SellerPayoutStatus.READY_FOR_RELEASE)
                .createdAt(LocalDateTime.now())
                .build();
        when(repository.findByPayoutIdAndSellerId("PAYOUT-1", "SELLER-1")).thenReturn(Optional.of(payout));
        when(payoutAccountRepository.existsBySellerIdAndStatus("SELLER-1", SellerPayoutAccountStatus.VERIFIED)).thenReturn(false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.releasePayout("SELLER-1", "PAYOUT-1"));

        assertEquals("Verified seller payout account is required before payout release: SELLER-1", ex.getMessage());
    }

    @Test
    void rejectsReleaseWhenPayoutIsNotReady() {
        SellerPayout payout = SellerPayout.builder()
                .payoutId("PAYOUT-1")
                .sellerId("SELLER-1")
                .status(SellerPayoutStatus.HELD)
                .build();
        when(repository.findByPayoutIdAndSellerId("PAYOUT-1", "SELLER-1")).thenReturn(Optional.of(payout));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.releasePayout("SELLER-1", "PAYOUT-1"));

        assertEquals("Only ready payouts can be released: PAYOUT-1", ex.getMessage());
    }

    @Test
    void marksHeldPayoutReadyForReleaseBySource() {
        SellerPayout payout = SellerPayout.builder()
                .payoutId("PAYOUT-1")
                .sellerId("SELLER-1")
                .sourceType("MARKETPLACE_ORDER")
                .sourceId("MORD-1")
                .status(SellerPayoutStatus.HELD)
                .build();
        when(repository.findBySourceTypeAndSourceId("MARKETPLACE_ORDER", "MORD-1")).thenReturn(Optional.of(payout));
        when(repository.save(any(SellerPayout.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SellerPayoutResponse response = service.markReadyForRelease("MARKETPLACE_ORDER", "MORD-1");

        assertEquals(SellerPayoutStatus.READY_FOR_RELEASE, response.getStatus());
    }

    @Test
    void marksHeldPayoutDisputedBySource() {
        SellerPayout payout = SellerPayout.builder()
                .payoutId("PAYOUT-1")
                .sellerId("SELLER-1")
                .sourceType("MARKETPLACE_ORDER")
                .sourceId("MORD-1")
                .status(SellerPayoutStatus.HELD)
                .build();
        when(repository.findBySourceTypeAndSourceId("MARKETPLACE_ORDER", "MORD-1")).thenReturn(Optional.of(payout));
        when(repository.save(any(SellerPayout.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SellerPayoutResponse response = service.markDisputed("MARKETPLACE_ORDER", "MORD-1");

        assertEquals(SellerPayoutStatus.DISPUTED, response.getStatus());
    }

    @Test
    void marksDisputedPayoutCancelledBySource() {
        SellerPayout payout = SellerPayout.builder()
                .payoutId("PAYOUT-1")
                .sellerId("SELLER-1")
                .sourceType("MARKETPLACE_ORDER")
                .sourceId("MORD-1")
                .status(SellerPayoutStatus.DISPUTED)
                .build();
        when(repository.findBySourceTypeAndSourceId("MARKETPLACE_ORDER", "MORD-1")).thenReturn(Optional.of(payout));
        when(repository.save(any(SellerPayout.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SellerPayoutResponse response = service.markCancelled("MARKETPLACE_ORDER", "MORD-1");

        assertEquals(SellerPayoutStatus.CANCELLED, response.getStatus());
    }

    @Test
    void providerPartialRefundMarksHeldPayoutDisputed() {
        SellerPayout payout = SellerPayout.builder()
                .payoutId("PAYOUT-1")
                .sellerId("SELLER-1")
                .sourceType("MARKETPLACE_ORDER")
                .sourceId("MORD-1")
                .status(SellerPayoutStatus.HELD)
                .build();
        when(repository.findBySourceTypeAndSourceId("MARKETPLACE_ORDER", "MORD-1")).thenReturn(Optional.of(payout));
        when(repository.save(any(SellerPayout.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SellerPayoutResponse response = service.applyProviderRefundStatus("MARKETPLACE_ORDER", "MORD-1", true).orElseThrow();

        assertEquals(SellerPayoutStatus.DISPUTED, response.getStatus());
    }

    @Test
    void providerFullRefundMarksHeldPayoutCancelled() {
        SellerPayout payout = SellerPayout.builder()
                .payoutId("PAYOUT-1")
                .sellerId("SELLER-1")
                .sourceType("MARKETPLACE_ORDER")
                .sourceId("MORD-1")
                .status(SellerPayoutStatus.HELD)
                .build();
        when(repository.findBySourceTypeAndSourceId("MARKETPLACE_ORDER", "MORD-1")).thenReturn(Optional.of(payout));
        when(repository.save(any(SellerPayout.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SellerPayoutResponse response = service.applyProviderRefundStatus("MARKETPLACE_ORDER", "MORD-1", false).orElseThrow();

        assertEquals(SellerPayoutStatus.CANCELLED, response.getStatus());
    }

    @Test
    void providerRefundAfterPayoutReleaseRequiresRecovery() {
        SellerPayout payout = SellerPayout.builder()
                .payoutId("PAYOUT-1")
                .sellerId("SELLER-1")
                .sourceType("MARKETPLACE_ORDER")
                .sourceId("MORD-1")
                .status(SellerPayoutStatus.RELEASED)
                .build();
        when(repository.findBySourceTypeAndSourceId("MARKETPLACE_ORDER", "MORD-1")).thenReturn(Optional.of(payout));
        when(repository.save(any(SellerPayout.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SellerPayoutResponse response = service.applyProviderRefundStatus("MARKETPLACE_ORDER", "MORD-1", false).orElseThrow();

        assertEquals(SellerPayoutStatus.RECOVERY_REQUIRED, response.getStatus());
    }

    @Test
    void marksRecoveryRequiredPayoutRecovered() {
        SellerPayout payout = SellerPayout.builder()
                .payoutId("PAYOUT-1")
                .sellerId("SELLER-1")
                .status(SellerPayoutStatus.RECOVERY_REQUIRED)
                .build();
        when(repository.findById("PAYOUT-1")).thenReturn(Optional.of(payout));
        when(repository.save(any(SellerPayout.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SellerPayoutResponse response = service.markRecovered("PAYOUT-1");

        assertEquals(SellerPayoutStatus.RECOVERED, response.getStatus());
    }

    @Test
    void rejectsRecoveredMarkWhenPayoutDoesNotRequireRecovery() {
        SellerPayout payout = SellerPayout.builder()
                .payoutId("PAYOUT-1")
                .sellerId("SELLER-1")
                .status(SellerPayoutStatus.RELEASED)
                .build();
        when(repository.findById("PAYOUT-1")).thenReturn(Optional.of(payout));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.markRecovered("PAYOUT-1"));

        assertEquals("Only recovery-required payouts can be marked recovered: PAYOUT-1", ex.getMessage());
    }
}

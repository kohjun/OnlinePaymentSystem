package com.example.payment.application.service;

import com.example.payment.domain.model.marketplace.SellerPayout;
import com.example.payment.domain.model.marketplace.SellerPayoutStatus;
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
    private final SellerPayoutService service = new SellerPayoutService(repository);

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
    void releasesHeldPayout() {
        SellerPayout payout = SellerPayout.builder()
                .payoutId("PAYOUT-1")
                .sellerId("SELLER-1")
                .sourceType("MARKETPLACE_ORDER")
                .sourceId("MORD-1")
                .grossAmount(new BigDecimal("100000"))
                .platformFee(new BigDecimal("10000.00"))
                .netAmount(new BigDecimal("90000.00"))
                .status(SellerPayoutStatus.HELD)
                .createdAt(LocalDateTime.now())
                .build();
        when(repository.findByPayoutIdAndSellerId("PAYOUT-1", "SELLER-1")).thenReturn(Optional.of(payout));
        when(repository.save(any(SellerPayout.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SellerPayoutResponse response = service.releasePayout("SELLER-1", "PAYOUT-1");

        assertEquals(SellerPayoutStatus.RELEASED, response.getStatus());
    }

    @Test
    void rejectsReleaseWhenPayoutIsNotHeld() {
        SellerPayout payout = SellerPayout.builder()
                .payoutId("PAYOUT-1")
                .sellerId("SELLER-1")
                .status(SellerPayoutStatus.RELEASED)
                .build();
        when(repository.findByPayoutIdAndSellerId("PAYOUT-1", "SELLER-1")).thenReturn(Optional.of(payout));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.releasePayout("SELLER-1", "PAYOUT-1"));

        assertEquals("Only held payouts can be released: PAYOUT-1", ex.getMessage());
    }
}

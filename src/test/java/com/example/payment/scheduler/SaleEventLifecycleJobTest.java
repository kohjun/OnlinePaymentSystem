package com.example.payment.scheduler;

import com.example.payment.application.service.RaffleService;
import com.example.payment.domain.model.marketplace.SaleEvent;
import com.example.payment.domain.model.marketplace.SaleEventStatus;
import com.example.payment.domain.model.marketplace.SaleType;
import com.example.payment.domain.repository.SaleEventRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SaleEventLifecycleJobTest {

    private final SaleEventRepository saleEventRepository = mock(SaleEventRepository.class);
    private final RaffleService raffleService = mock(RaffleService.class);
    private final SaleEventLifecycleJob job = new SaleEventLifecycleJob(saleEventRepository, raffleService);

    @Test
    void closesDueRafflesAndNonCompetitiveEvents() {
        SaleEvent raffle = SaleEvent.builder()
                .saleEventId("EVT-RAFFLE")
                .saleType(SaleType.RAFFLE)
                .status(SaleEventStatus.LIVE)
                .endsAt(LocalDateTime.now().minusMinutes(1))
                .build();
        when(saleEventRepository.findBySaleTypeAndStatusAndEndsAtLessThanEqual(
                eq(SaleType.RAFFLE), eq(SaleEventStatus.LIVE), any(LocalDateTime.class)))
                .thenReturn(List.of(raffle));

        job.closeDueEvents();

        verify(raffleService).drawDueRaffle("EVT-RAFFLE");
        verify(saleEventRepository).endDueEvents(
                eq(List.of(SaleType.FIXED_PRICE, SaleType.DROP)),
                eq(SaleEventStatus.LIVE),
                eq(SaleEventStatus.ENDED),
                any(LocalDateTime.class));
    }
}

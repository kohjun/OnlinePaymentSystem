package com.example.payment.application.service;

import com.example.payment.domain.model.marketplace.ListingStatus;
import com.example.payment.domain.model.marketplace.MarketplaceListing;
import com.example.payment.domain.model.marketplace.SaleEvent;
import com.example.payment.domain.model.marketplace.SaleEventActivationMode;
import com.example.payment.domain.model.marketplace.SaleEventStatus;
import com.example.payment.domain.model.marketplace.SaleType;
import com.example.payment.domain.repository.MarketplaceListingRepository;
import com.example.payment.domain.repository.SaleEventRepository;
import com.example.payment.infrastructure.messaging.outbox.OutboxEventService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SaleEventActivationServiceTest {

    private final SaleEventRepository saleEventRepository = mock(SaleEventRepository.class);
    private final MarketplaceListingRepository listingRepository = mock(MarketplaceListingRepository.class);
    private final OutboxEventService outboxEventService = mock(OutboxEventService.class);
    private final MarketplaceRealtimeService realtimeService = mock(MarketplaceRealtimeService.class);
    private final SaleEventActivationService service = new SaleEventActivationService(
            saleEventRepository, listingRepository, outboxEventService, realtimeService);

    @Test
    void activatesDueEventWithoutChangingScheduledStart() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        SaleEvent event = scheduledEvent(now.minusMinutes(1), now.plusHours(1));
        when(saleEventRepository.findDueScheduledForUpdate(
                eq(SaleEventStatus.SCHEDULED), eq(now), any(Pageable.class)))
                .thenReturn(List.of(event));
        when(listingRepository.findById("LIST-1")).thenReturn(Optional.of(MarketplaceListing.builder()
                .listingId("LIST-1")
                .status(ListingStatus.ACTIVE)
                .build()));

        SaleEventActivationService.ActivationBatchResult result = service.activateDueEvents(now, 100);

        assertEquals(List.of("EVT-1"), result.activatedEventIds());
        assertEquals(SaleEventStatus.LIVE, event.getStatus());
        assertEquals(now.minusMinutes(1), event.getStartsAt());
        verify(realtimeService).publishAuction(eq("EVT-1"), eq("sale-started"), any());
        verify(outboxEventService).record(
                eq("SALE_EVENT"), eq("EVT-1"), eq("SALE_STARTED"),
                eq("marketplace-sale-events"), eq("EVT-1"), any());
    }

    @Test
    void recordsActivationFailureWhenListingIsNotActive() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        SaleEvent event = scheduledEvent(now.minusMinutes(1), now.plusHours(1));
        when(saleEventRepository.findDueScheduledForUpdate(
                eq(SaleEventStatus.SCHEDULED), eq(now), any(Pageable.class)))
                .thenReturn(List.of(event));
        when(listingRepository.findById("LIST-1")).thenReturn(Optional.of(MarketplaceListing.builder()
                .listingId("LIST-1")
                .status(ListingStatus.PAUSED)
                .build()));

        SaleEventActivationService.ActivationBatchResult result = service.activateDueEvents(now, 100);

        assertEquals(List.of("EVT-1"), result.failedEventIds());
        assertEquals(SaleEventStatus.ACTIVATION_FAILED, event.getStatus());
        assertEquals("Listing is not active at the scheduled start time.", event.getActivationFailureReason());
        verify(realtimeService).publishAuction(eq("EVT-1"), eq("sale-activation-failed"), any());
    }

    private SaleEvent scheduledEvent(LocalDateTime startsAt, LocalDateTime endsAt) {
        return SaleEvent.builder()
                .saleEventId("EVT-1")
                .listingId("LIST-1")
                .sellerId("SELLER-1")
                .productId("PROD-1")
                .saleType(SaleType.AUCTION)
                .status(SaleEventStatus.SCHEDULED)
                .activationMode(SaleEventActivationMode.SCHEDULED)
                .startsAt(startsAt)
                .endsAt(endsAt)
                .build();
    }
}

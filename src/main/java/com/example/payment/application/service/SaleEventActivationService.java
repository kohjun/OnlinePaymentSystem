package com.example.payment.application.service;

import com.example.payment.domain.model.marketplace.ListingStatus;
import com.example.payment.domain.model.marketplace.MarketplaceListing;
import com.example.payment.domain.model.marketplace.SaleEvent;
import com.example.payment.domain.model.marketplace.SaleEventStatus;
import com.example.payment.domain.model.marketplace.SaleType;
import com.example.payment.domain.repository.MarketplaceListingRepository;
import com.example.payment.domain.repository.SaleEventRepository;
import com.example.payment.infrastructure.messaging.outbox.OutboxEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class SaleEventActivationService {

    private final SaleEventRepository saleEventRepository;
    private final MarketplaceListingRepository listingRepository;
    private final OutboxEventService outboxEventService;
    private final MarketplaceRealtimeService realtimeService;

    @Transactional
    public ActivationBatchResult activateDueEvents(LocalDateTime now, int batchSize) {
        List<SaleEvent> events = saleEventRepository.findDueScheduledForUpdate(
                SaleEventStatus.SCHEDULED,
                now,
                PageRequest.of(0, Math.max(1, batchSize))
        );
        List<String> activated = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (SaleEvent event : events) {
            String failureReason = activationFailure(event, now);
            if (failureReason != null) {
                event.setStatus(SaleEventStatus.ACTIVATION_FAILED);
                event.setActivationFailureReason(failureReason);
                saleEventRepository.save(event);
                record(event, "SALE_ACTIVATION_FAILED");
                publish(event, "sale-activation-failed");
                failed.add(event.getSaleEventId());
                continue;
            }

            event.setStatus(SaleEventStatus.LIVE);
            event.setActivationFailureReason(null);
            saleEventRepository.save(event);
            record(event, "SALE_STARTED");
            publish(event, "sale-started");
            activated.add(event.getSaleEventId());
        }
        saleEventRepository.flush();
        if (!activated.isEmpty() || !failed.isEmpty()) {
            log.info("Processed scheduled sale events: activated={}, failed={}", activated.size(), failed.size());
        }
        return new ActivationBatchResult(activated, failed);
    }

    private String activationFailure(SaleEvent event, LocalDateTime now) {
        MarketplaceListing listing = listingRepository.findById(event.getListingId()).orElse(null);
        if (listing == null) {
            return "Listing no longer exists.";
        }
        if (listing.getStatus() != ListingStatus.ACTIVE) {
            return "Listing is not active at the scheduled start time.";
        }
        if (event.getEndsAt() != null && !event.getEndsAt().isAfter(now)) {
            return "Sale schedule expired before activation.";
        }
        return null;
    }

    private void record(SaleEvent event, String eventType) {
        outboxEventService.record(
                "SALE_EVENT",
                event.getSaleEventId(),
                eventType,
                "marketplace-sale-events",
                event.getSaleEventId(),
                payload(event)
        );
    }

    private void publish(SaleEvent event, String eventName) {
        if (event.getSaleType() == SaleType.AUCTION) {
            realtimeService.publishAuction(event.getSaleEventId(), eventName, payload(event));
        } else if (event.getSaleType() == SaleType.RAFFLE) {
            realtimeService.publishRaffle(event.getSaleEventId(), eventName, payload(event));
        }
    }

    private Map<String, Object> payload(SaleEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("saleEventId", event.getSaleEventId());
        payload.put("status", event.getStatus());
        payload.put("activationMode", event.getActivationMode());
        payload.put("startsAt", event.getStartsAt());
        if (event.getEndsAt() != null) {
            payload.put("endsAt", event.getEndsAt());
        }
        if (event.getActivationFailureReason() != null) {
            payload.put("activationFailureReason", event.getActivationFailureReason());
        }
        return payload;
    }

    public record ActivationBatchResult(List<String> activatedEventIds, List<String> failedEventIds) {
    }
}

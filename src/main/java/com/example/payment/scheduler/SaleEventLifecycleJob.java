package com.example.payment.scheduler;

import com.example.payment.application.service.MarketplaceCheckoutException;
import com.example.payment.application.service.RaffleService;
import com.example.payment.domain.model.marketplace.SaleEvent;
import com.example.payment.domain.model.marketplace.SaleEventStatus;
import com.example.payment.domain.model.marketplace.SaleType;
import com.example.payment.domain.repository.SaleEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.marketplace.lifecycle.enabled", havingValue = "true", matchIfMissing = true)
public class SaleEventLifecycleJob {

    private final SaleEventRepository saleEventRepository;
    private final RaffleService raffleService;

    @Scheduled(fixedDelayString = "${app.marketplace.lifecycle.fixed-delay-ms:5000}")
    public void closeDueEvents() {
        LocalDateTime now = LocalDateTime.now();
        closeDueRaffles(now);

        int ended = saleEventRepository.endDueEvents(
                List.of(SaleType.FIXED_PRICE, SaleType.DROP),
                SaleEventStatus.LIVE,
                SaleEventStatus.ENDED,
                now);
        if (ended > 0) {
            log.info("Closed {} expired fixed-price or drop sale events", ended);
        }
    }

    private void closeDueRaffles(LocalDateTime now) {
        List<SaleEvent> dueRaffles = saleEventRepository.findBySaleTypeAndStatusAndEndsAtLessThanEqual(
                SaleType.RAFFLE,
                SaleEventStatus.LIVE,
                now);
        for (SaleEvent event : dueRaffles) {
            try {
                raffleService.drawDueRaffle(event.getSaleEventId());
            } catch (MarketplaceCheckoutException exception) {
                log.warn("Raffle lifecycle close skipped: saleEventId={}, reason={}",
                        event.getSaleEventId(), exception.getMessage());
            } catch (RuntimeException exception) {
                log.error("Raffle lifecycle close failed: saleEventId={}", event.getSaleEventId(), exception);
            }
        }
    }
}

package com.example.payment.scheduler;

import com.example.payment.application.service.AuctionService;
import com.example.payment.application.service.MarketplaceCheckoutException;
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
@ConditionalOnProperty(name = "app.auction.auto-close-enabled", havingValue = "true", matchIfMissing = true)
public class AuctionAutoCloseJob {

    private final SaleEventRepository saleEventRepository;
    private final AuctionService auctionService;

    @Scheduled(fixedDelayString = "${app.auction.auto-close-fixed-delay-ms:5000}")
    public void closeDueAuctions() {
        List<SaleEvent> dueAuctions = saleEventRepository.findBySaleTypeAndStatusAndEndsAtLessThanEqual(
                SaleType.AUCTION,
                SaleEventStatus.LIVE,
                LocalDateTime.now()
        );
        for (SaleEvent event : dueAuctions) {
            try {
                auctionService.closeDueAuction(event.getSaleEventId());
            } catch (MarketplaceCheckoutException e) {
                log.warn("Auction auto-close skipped: saleEventId={}, reason={}", event.getSaleEventId(), e.getMessage());
            } catch (RuntimeException e) {
                log.error("Auction auto-close failed: saleEventId={}", event.getSaleEventId(), e);
            }
        }
    }
}

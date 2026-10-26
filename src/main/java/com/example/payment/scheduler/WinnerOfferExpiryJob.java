package com.example.payment.scheduler;

import com.example.payment.application.service.AuctionService;
import com.example.payment.application.service.RaffleService;
import com.example.payment.domain.model.marketplace.AuctionSettlementStatus;
import com.example.payment.domain.model.marketplace.RaffleCheckoutStatus;
import com.example.payment.domain.repository.AuctionSettlementRepository;
import com.example.payment.domain.repository.RaffleWinnerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
        name = "app.marketplace.winner-offer-expiry-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class WinnerOfferExpiryJob {

    private final AuctionSettlementRepository auctionSettlementRepository;
    private final RaffleWinnerRepository raffleWinnerRepository;
    private final AuctionService auctionService;
    private final RaffleService raffleService;

    @Scheduled(fixedDelayString = "${app.marketplace.winner-offer-expiry-fixed-delay-ms:5000}")
    public void transferExpiredOffers() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        auctionSettlementRepository.findByStatusAndCheckoutExpiresAtBefore(
                        AuctionSettlementStatus.AWAITING_PAYMENT, now)
                .forEach(settlement -> transferAuctionOffer(settlement.getSettlementId(), now));
        raffleWinnerRepository.findByCheckoutStatusAndCheckoutExpiresAtBefore(
                        RaffleCheckoutStatus.PENDING, now)
                .forEach(winner -> transferRaffleOffer(winner.getWinnerId(), now));
    }

    private void transferAuctionOffer(String settlementId, LocalDateTime now) {
        try {
            auctionService.expireWinnerOffer(settlementId, now);
        } catch (RuntimeException failure) {
            log.error("Auction winner offer transfer failed: settlementId={}", settlementId, failure);
        }
    }

    private void transferRaffleOffer(String winnerId, LocalDateTime now) {
        try {
            raffleService.expireWinnerOffer(winnerId, now);
        } catch (RuntimeException failure) {
            log.error("Raffle winner offer transfer failed: winnerId={}", winnerId, failure);
        }
    }
}

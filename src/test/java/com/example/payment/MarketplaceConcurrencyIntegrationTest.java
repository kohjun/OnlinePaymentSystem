package com.example.payment;

import com.example.payment.application.service.AuctionService;
import com.example.payment.application.service.MarketplaceCheckoutException;
import com.example.payment.application.service.RaffleService;
import com.example.payment.application.service.StandbyQueueService;
import com.example.payment.domain.model.marketplace.AuctionBidStatus;
import com.example.payment.domain.model.marketplace.ListingStatus;
import com.example.payment.domain.model.marketplace.RaffleEntryStatus;
import com.example.payment.domain.model.marketplace.SaleEventStatus;
import com.example.payment.domain.repository.AuctionBidRepository;
import com.example.payment.domain.repository.AuctionSettlementRepository;
import com.example.payment.domain.repository.MarketplaceListingRepository;
import com.example.payment.domain.repository.RaffleEntryRepository;
import com.example.payment.domain.repository.RaffleWinnerRepository;
import com.example.payment.domain.repository.SaleEventRepository;
import com.example.payment.infrastructure.tenancy.TenantContext;
import com.example.payment.presentation.dto.request.AuctionBidRequest;
import com.example.payment.presentation.dto.request.RaffleEntryRequest;
import com.example.payment.presentation.dto.response.RaffleEntryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class MarketplaceConcurrencyIntegrationTest extends TestcontainersIntegrationSupport {

    private static final String AUCTION_EVENT_ID = "EVT-AUCTION-ROLEX";
    private static final String RAFFLE_EVENT_ID = "EVT-DRAW-NIKE";

    @Autowired
    private AuctionService auctionService;

    @Autowired
    private RaffleService raffleService;

    @Autowired
    private SaleEventRepository saleEventRepository;

    @Autowired
    private MarketplaceListingRepository listingRepository;

    @Autowired
    private AuctionBidRepository auctionBidRepository;

    @Autowired
    private AuctionSettlementRepository auctionSettlementRepository;

    @Autowired
    private RaffleEntryRepository raffleEntryRepository;

    @Autowired
    private RaffleWinnerRepository raffleWinnerRepository;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private StandbyQueueService standbyQueueService;

    @BeforeEach
    void resetMarketplaceFixtures() {
        auctionSettlementRepository.deleteAll();
        auctionBidRepository.deleteAll();
        raffleWinnerRepository.deleteAll();
        raffleEntryRepository.deleteAll();

        var auction = saleEventRepository.findById(AUCTION_EVENT_ID).orElseThrow();
        auction.setStatus(SaleEventStatus.LIVE);
        auction.setStartsAt(LocalDateTime.now().minusMinutes(5));
        auction.setEndsAt(LocalDateTime.now().plusMinutes(30));
        auction.setPrice(new BigDecimal("8500000.00"));
        auction.setMinBidIncrement(new BigDecimal("100000.00"));
        saleEventRepository.save(auction);

        var raffle = saleEventRepository.findById(RAFFLE_EVENT_ID).orElseThrow();
        raffle.setStatus(SaleEventStatus.LIVE);
        raffle.setStartsAt(LocalDateTime.now().minusMinutes(5));
        raffle.setEndsAt(LocalDateTime.now().plusMinutes(30));
        saleEventRepository.save(raffle);

        listingRepository.findById(auction.getListingId()).ifPresent(listing -> {
            listing.setStatus(ListingStatus.ACTIVE);
            listingRepository.save(listing);
        });
        listingRepository.findById(raffle.getListingId()).ifPresent(listing -> {
            listing.setStatus(ListingStatus.ACTIVE);
            listingRepository.save(listing);
        });

        redisTemplate.delete("auction:highest_bid:" + AUCTION_EVENT_ID);
        redisTemplate.delete("raffle:entries:" + RAFFLE_EVENT_ID);
        Set<String> queueKeys = redisTemplate.keys("everysale:queue:load-tenant:*");
        if (queueKeys != null && !queueKeys.isEmpty()) {
            redisTemplate.delete(queueKeys);
        }
        ReflectionTestUtils.setField(standbyQueueService, "queueEnabled", true);
        ReflectionTestUtils.setField(standbyQueueService, "promoteSize", 50);
        ReflectionTestUtils.setField(standbyQueueService, "maxActiveUsers", 50);
        ReflectionTestUtils.setField(standbyQueueService, "activeLeaseSeconds", 60L);
    }

    @AfterEach
    void restoreQueueConfiguration() {
        ReflectionTestUtils.setField(standbyQueueService, "queueEnabled", false);
        TenantContext.clear();
    }

    @Test
    void fiftyConcurrentBidsLeaveExactlyOneHighestWinner() throws Exception {
        int requestCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        for (int i = 1; i <= requestCount; i++) {
            int sequence = i;
            futures.add(executor.submit(() -> {
                start.await();
                AuctionBidRequest request = new AuctionBidRequest();
                request.setCustomerId("AUCTION-CUSTOMER-" + sequence);
                request.setBidAmount(new BigDecimal("8500000.00")
                        .add(new BigDecimal("100000.00").multiply(BigDecimal.valueOf(sequence))));
                request.setIdempotencyKey("auction-load-" + sequence);
                try {
                    auctionService.placeBid(AUCTION_EVENT_ID, request);
                    return true;
                } catch (MarketplaceCheckoutException expectedRaceRejection) {
                    return false;
                }
            }));
        }

        start.countDown();
        for (Future<Boolean> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        var status = auctionService.status(AUCTION_EVENT_ID);
        assertEquals(new BigDecimal("13500000.00"), status.getHighestBid());

        // 공개 경매 상태는 입찰자를 별칭으로 가린다. 실제 고객 식별자가
        // 그대로 새어나가지 않는지 함께 확인한다.
        assertTrue(status.getHighestBidder().startsWith("bidder-"));
        assertFalse(status.getHighestBidder().contains("AUCTION-CUSTOMER-"));

        var winningBids = auctionBidRepository.findAll().stream()
                .filter(bid -> AUCTION_EVENT_ID.equals(bid.getSaleEventId()))
                .filter(bid -> bid.getStatus() == AuctionBidStatus.WINNING)
                .toList();
        assertEquals(1, winningBids.size());
        assertEquals("AUCTION-CUSTOMER-50", winningBids.get(0).getCustomerId());
    }

    @Test
    void concurrentDuplicateRaffleEntriesReturnOneDatabaseEntry() throws Exception {
        int requestCount = 30;
        ExecutorService executor = Executors.newFixedThreadPool(15);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RaffleEntryResponse>> futures = new ArrayList<>();

        for (int i = 0; i < requestCount; i++) {
            int sequence = i;
            futures.add(executor.submit(() -> {
                start.await();
                RaffleEntryRequest request = new RaffleEntryRequest();
                request.setCustomerId("RAFFLE-SAME-CUSTOMER");
                request.setIdempotencyKey("raffle-duplicate-" + sequence);
                return raffleService.enter(RAFFLE_EVENT_ID, request);
            }));
        }

        start.countDown();
        Set<String> entryIds = new HashSet<>();
        for (Future<RaffleEntryResponse> future : futures) {
            entryIds.add(future.get(60, TimeUnit.SECONDS).getEntryId());
        }
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(1, entryIds.size());
        assertEquals(1L, raffleEntryRepository.countBySaleEventIdAndStatus(
                RAFFLE_EVENT_ID,
                RaffleEntryStatus.ENTERED
        ));
    }

    @Test
    void oneHundredDistinctRaffleCustomersArePersistedWithoutLoss() throws Exception {
        int requestCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RaffleEntryResponse>> futures = new ArrayList<>();

        for (int i = 0; i < requestCount; i++) {
            int sequence = i;
            futures.add(executor.submit(() -> {
                start.await();
                RaffleEntryRequest request = new RaffleEntryRequest();
                request.setCustomerId("RAFFLE-CUSTOMER-" + sequence);
                request.setIdempotencyKey("raffle-load-" + sequence);
                return raffleService.enter(RAFFLE_EVENT_ID, request);
            }));
        }

        start.countDown();
        for (Future<RaffleEntryResponse> future : futures) {
            future.get(90, TimeUnit.SECONDS);
        }
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(requestCount, raffleEntryRepository.countBySaleEventIdAndStatus(
                RAFFLE_EVENT_ID,
                RaffleEntryStatus.ENTERED
        ));
    }

    @Test
    void oneHundredConcurrentQueueJoinsRespectFiftyActiveLeaseCapacity() throws Exception {
        int requestCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<StandbyQueueService.QueueStatus>> futures = new ArrayList<>();

        for (int i = 0; i < requestCount; i++) {
            int sequence = i;
            futures.add(executor.submit(() -> {
                TenantContext.set("load-tenant", "load-partner", "queue-" + sequence);
                try {
                    start.await();
                    return standbyQueueService.join("QUEUE-CUSTOMER-" + sequence);
                } finally {
                    TenantContext.clear();
                }
            }));
        }

        start.countDown();
        for (Future<StandbyQueueService.QueueStatus> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        standbyQueueService.promoteAllTenants();
        long activeCount = 0;
        Set<Long> waitingRanks = new HashSet<>();
        TenantContext.set("load-tenant", "load-partner", "queue-status");
        try {
            for (int i = 0; i < requestCount; i++) {
                StandbyQueueService.QueueStatus status = standbyQueueService.status("QUEUE-CUSTOMER-" + i);
                if ("ACTIVE".equals(status.status())) {
                    activeCount++;
                } else if ("WAITING".equals(status.status())) {
                    waitingRanks.add(status.rank());
                }
            }
        } finally {
            TenantContext.clear();
        }

        assertEquals(50L, activeCount);
        assertEquals(50, waitingRanks.size());
        assertTrue(waitingRanks.contains(1L));
        assertTrue(waitingRanks.contains(50L));
    }
}

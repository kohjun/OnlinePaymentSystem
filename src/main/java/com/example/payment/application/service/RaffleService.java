package com.example.payment.application.service;

import com.example.payment.domain.model.inventory.Inventory;
import com.example.payment.domain.model.marketplace.ListingStatus;
import com.example.payment.domain.model.marketplace.MarketplaceListing;
import com.example.payment.domain.model.marketplace.RaffleCheckoutStatus;
import com.example.payment.domain.model.marketplace.RaffleEntry;
import com.example.payment.domain.model.marketplace.RaffleEntryStatus;
import com.example.payment.domain.model.marketplace.RaffleWinner;
import com.example.payment.domain.model.marketplace.SaleEvent;
import com.example.payment.domain.model.marketplace.SaleEventStatus;
import com.example.payment.domain.model.marketplace.SaleType;
import com.example.payment.domain.repository.InventoryRepository;
import com.example.payment.domain.repository.MarketplaceListingRepository;
import com.example.payment.domain.repository.RaffleEntryRepository;
import com.example.payment.domain.repository.RaffleWinnerRepository;
import com.example.payment.domain.repository.SaleEventRepository;
import com.example.payment.infrastructure.util.AfterCommitExecutor;
import com.example.payment.presentation.dto.request.RaffleDrawRequest;
import com.example.payment.presentation.dto.request.RaffleEntryRequest;
import com.example.payment.presentation.dto.response.RaffleEntryResponse;
import com.example.payment.presentation.dto.response.RaffleStatusResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RaffleService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final SaleEventRepository saleEventRepository;
    private final MarketplaceListingRepository marketplaceListingRepository;
    private final InventoryRepository inventoryRepository;
    private final RaffleEntryRepository raffleEntryRepository;
    private final RaffleWinnerRepository raffleWinnerRepository;
    private final MarketplaceRealtimeService marketplaceRealtimeService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.marketplace.winner-checkout-ttl-minutes:30}")
    private long winnerCheckoutTtlMinutes;

    @Value("${app.marketplace.raffle.allow-operator-seed:false}")
    private boolean allowOperatorSeed;

    @Transactional
    public RaffleEntryResponse enter(String eventId, RaffleEntryRequest request) {
        SaleEvent event = requireRaffleEventForUpdate(eventId);
        MarketplaceListing listing = requireActiveListing(event);
        validateRaffleOpen(event, listing);

        RaffleEntry existing = raffleEntryRepository
                .findBySaleEventIdAndCustomerId(eventId, request.getCustomerId())
                .orElse(null);
        if (existing != null) {
            return toEntryResponse(existing);
        }

        LocalDateTime now = LocalDateTime.now();
        String entryId = "RFENT-" + shortId();
        int inserted = raffleEntryRepository.insertIfAbsent(
                entryId,
                eventId,
                request.getCustomerId(),
                RaffleEntryStatus.ENTERED.name(),
                now,
                now
        );
        RaffleEntry entry = inserted == 1
                ? RaffleEntry.builder()
                        .entryId(entryId)
                        .saleEventId(eventId)
                        .customerId(request.getCustomerId())
                        .status(RaffleEntryStatus.ENTERED)
                        .createdAt(now)
                        .updatedAt(now)
                        .build()
                : raffleEntryRepository.findBySaleEventIdAndCustomerId(eventId, request.getCustomerId())
                        .orElseThrow(() -> new MarketplaceCheckoutException(
                                HttpStatus.CONFLICT,
                                "The raffle entry is being committed. Please retry."
                        ));

        RaffleStatusResponse snapshot = status(eventId, request.getCustomerId());
        AfterCommitExecutor.run(() -> {
            String cacheKey = "raffle:entries:" + eventId;
            redisTemplate.opsForSet().add(cacheKey, request.getCustomerId());
            redisTemplate.expire(cacheKey, Duration.ofHours(24));
            marketplaceRealtimeService.publishRaffle(eventId, "entry-count-changed", snapshot);
        });
        return toEntryResponse(entry);
    }

    @Deprecated(forRemoval = true)
    private RaffleEntryResponse enterLegacy(String eventId, RaffleEntryRequest request) {
        // 1. 멱등성 검증 (idempotencyKey가 있는 경우)
        if (request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank()) {
            String idempotencyKey = request.getIdempotencyKey();
            String cachedResponseStr = (String) redisTemplate.opsForValue().get("idempotency:raffle:" + idempotencyKey);
            if (cachedResponseStr != null) {
                try {
                    return objectMapper.readValue(cachedResponseStr, RaffleEntryResponse.class);
                } catch (Exception e) {
                    // Ignore parsing error and proceed
                }
            }
        }

        SaleEvent event = requireRaffleEvent(eventId);
        MarketplaceListing listing = requireActiveListing(event);
        validateRaffleOpen(event, listing);

        // 2. Redis Set 기반 Fast-Filter 중복 검증
        String cacheKey = "raffle:entries:" + eventId;
        ensureRaffleEntriesCached(eventId); // 캐시 워밍업 보장

        Boolean isMember = redisTemplate.opsForSet().isMember(cacheKey, request.getCustomerId());
        if (Boolean.TRUE.equals(isMember)) {
            RaffleEntry existing = findCommittedEntry(eventId, request.getCustomerId());
            RaffleEntryResponse response = toEntryResponse(existing);
            cacheIdempotencyIfNeeded(request.getIdempotencyKey(), response);
            return response;
        }

        RaffleEntry existing = raffleEntryRepository
                .findBySaleEventIdAndCustomerId(eventId, request.getCustomerId())
                .orElse(null);
        if (existing != null) {
            // 만약 캐시에는 없는데 DB에 이미 있었다면(캐시 유실 등의 경우), 캐시 채우고 반환
            redisTemplate.opsForSet().add(cacheKey, request.getCustomerId());
            RaffleEntryResponse response = toEntryResponse(existing);
            cacheIdempotencyIfNeeded(request.getIdempotencyKey(), response);
            return response;
        }

        LocalDateTime now = LocalDateTime.now();
        String entryId = "RFENT-" + shortId();
        int inserted = raffleEntryRepository.insertIfAbsent(
                entryId,
                eventId,
                request.getCustomerId(),
                RaffleEntryStatus.ENTERED.name(),
                now,
                now
        );
        RaffleEntry entry = inserted == 1
                ? RaffleEntry.builder()
                        .entryId(entryId)
                        .saleEventId(eventId)
                        .customerId(request.getCustomerId())
                        .status(RaffleEntryStatus.ENTERED)
                        .createdAt(now)
                        .updatedAt(now)
                        .build()
                : findCommittedEntry(eventId, request.getCustomerId());

        // DB 저장 성공 시 Redis Set에 추가
        redisTemplate.opsForSet().add(cacheKey, request.getCustomerId());

        RaffleEntryResponse response = toEntryResponse(entry);
        cacheIdempotencyIfNeeded(request.getIdempotencyKey(), response);

        marketplaceRealtimeService.publishRaffle(eventId, "entry-count-changed", status(eventId, request.getCustomerId()));
        return response;
    }

    private RaffleEntry findCommittedEntry(String eventId, String customerId) {
        for (int attempt = 0; attempt < 10; attempt++) {
            RaffleEntry existing = raffleEntryRepository
                    .findBySaleEventIdAndCustomerId(eventId, customerId)
                    .orElse(null);
            if (existing != null) {
                return existing;
            }
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "이미 처리 중인 응모 요청입니다. 잠시 후 다시 확인해 주세요.");
    }

    private void ensureRaffleEntriesCached(String eventId) {
        String cacheKey = "raffle:entries:" + eventId;
        Boolean exists = redisTemplate.hasKey(cacheKey);
        if (Boolean.FALSE.equals(exists)) {
            // Lazy load all customer IDs who entered this raffle from the DB
            List<RaffleEntry> entries = raffleEntryRepository.findBySaleEventIdAndStatusOrderByCreatedAtAsc(eventId, RaffleEntryStatus.ENTERED);
            redisTemplate.opsForSet().add(cacheKey, "DUMMY_VALUE_EXCEPT_CUSTOMER"); // prevent empty set issues
            if (!entries.isEmpty()) {
                for (RaffleEntry entry : entries) {
                    redisTemplate.opsForSet().add(cacheKey, entry.getCustomerId());
                }
            }
            redisTemplate.expire(cacheKey, Duration.ofHours(24));
        }
    }

    private void cacheIdempotencyIfNeeded(String idempotencyKey, RaffleEntryResponse response) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            try {
                String responseStr = objectMapper.writeValueAsString(response);
                redisTemplate.opsForValue().set("idempotency:raffle:" + idempotencyKey, responseStr, Duration.ofMinutes(10));
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    @Transactional(readOnly = true)
    public RaffleStatusResponse status(String eventId, String customerId) {
        SaleEvent event = requireRaffleEvent(eventId);
        List<RaffleWinner> winners = raffleWinnerRepository.findBySaleEventIdOrderByCreatedAtAsc(eventId);
        RaffleWinner customerWinner = customerId == null ? null :
                raffleWinnerRepository.findBySaleEventIdAndCustomerId(eventId, customerId).orElse(null);
        RaffleEntry customerEntry = customerId == null ? null :
                raffleEntryRepository.findBySaleEventIdAndCustomerId(eventId, customerId).orElse(null);
        boolean entered = customerEntry != null;

        return RaffleStatusResponse.builder()
                .saleEventId(eventId)
                .eventStatus(event.getStatus())
                .entryCount(raffleEntryRepository.countBySaleEventId(eventId))
                .winnerCount(winners.size())
                .completedCheckoutCount(raffleWinnerRepository.countBySaleEventIdAndCheckoutStatus(eventId, RaffleCheckoutStatus.COMPLETED))
                .entered(customerId == null ? null : entered)
                .winner(customerId == null ? null : customerWinner != null)
                .drawn(!winners.isEmpty() || event.getStatus() == SaleEventStatus.ENDED)
                .entryStatus(customerEntry != null ? customerEntry.getStatus() : null)
                .checkoutStatus(customerWinner != null ? customerWinner.getCheckoutStatus() : null)
                .winnerCustomerIds(List.of())
                .winnerAliases(winners.stream().map(winner -> publicAlias(winner.getCustomerId())).toList())
                .drawSeedCommitment(winners.isEmpty() ? null : winners.get(0).getDrawSeedCommitment())
                .entrySnapshotHash(winners.isEmpty() ? null : winners.get(0).getEntrySnapshotHash())
                .endsAt(event.getEndsAt())
                .checkoutExpiresAt(checkoutExpiresAt(winners, customerWinner))
                .build();
    }

    @Transactional
    public RaffleStatusResponse draw(String eventId, RaffleDrawRequest request) {
        SaleEvent event = requireRaffleEventForUpdate(eventId);
        requireActiveListing(event);

        if (raffleWinnerRepository.countBySaleEventId(eventId) > 0) {
            return status(eventId, null);
        }

        List<RaffleEntry> entries = new ArrayList<>(
                raffleEntryRepository.findBySaleEventIdAndStatusOrderByCreatedAtAsc(eventId, RaffleEntryStatus.ENTERED)
        );
        if (entries.isEmpty()) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "응모자가 없습니다.");
        }

        Inventory inventory = inventoryRepository.findById(event.getProductId())
                .orElseThrow(() -> new MarketplaceCheckoutException(HttpStatus.NOT_FOUND, "Inventory not found: " + event.getProductId()));
        int requestedWinners = request.getWinnerCount() != null ? request.getWinnerCount() : event.getStockQuantity();
        int winnerCount = Math.min(Math.min(requestedWinners, inventory.getAvailableQuantity()), entries.size());
        if (winnerCount <= 0) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "당첨 가능한 재고가 없습니다.");
        }

        String seed = allowOperatorSeed && request.getSeed() != null && !request.getSeed().trim().isEmpty()
                ? request.getSeed().trim()
                : secureSeed();
        String entrySnapshotHash = sha256(entries.stream()
                .map(entry -> entry.getEntryId() + ":" + entry.getCustomerId())
                .reduce((left, right) -> left + "|" + right)
                .orElse(""));
        String seedCommitment = sha256(seed);
        entries.sort(Comparator.comparing(entry -> drawRank(seed, entry)));

        List<RaffleEntry> winners = entries.subList(0, winnerCount);
        LocalDateTime checkoutExpiresAt = LocalDateTime.now().plusMinutes(winnerCheckoutTtlMinutes);
        for (RaffleEntry entry : entries) {
            entry.setStatus(winners.contains(entry) ? RaffleEntryStatus.WINNER : RaffleEntryStatus.NOT_SELECTED);
            raffleEntryRepository.save(entry);
        }

        for (RaffleEntry winner : winners) {
            raffleWinnerRepository.save(RaffleWinner.builder()
                    .winnerId("RFWIN-" + shortId())
                    .saleEventId(eventId)
                    .entryId(winner.getEntryId())
                    .customerId(winner.getCustomerId())
                    .checkoutStatus(RaffleCheckoutStatus.PENDING)
                    .drawSeed(seed)
                    .drawSeedCommitment(seedCommitment)
                    .entrySnapshotHash(entrySnapshotHash)
                    .drawnBy(defaultText(request.getOperatorId(), "system"))
                    .createdAt(LocalDateTime.now())
                    .checkoutExpiresAt(checkoutExpiresAt)
                    .build());
        }

        event.setStatus(SaleEventStatus.ENDED);
        event.setEndsAt(LocalDateTime.now());
        saleEventRepository.save(event);

        RaffleStatusResponse response = status(eventId, null);
        AfterCommitExecutor.run(() -> marketplaceRealtimeService.publishRaffle(
                eventId, "raffle-drawn", response));
        return response;
    }

    @Transactional
    public RaffleStatusResponse drawDueRaffle(String eventId) {
        SaleEvent event = requireRaffleEventForUpdate(eventId);
        if (event.getStatus() != SaleEventStatus.LIVE) {
            return status(eventId, null);
        }
        LocalDateTime now = LocalDateTime.now();
        if (event.getEndsAt() == null || event.getEndsAt().isAfter(now)) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT,
                    "Raffle has not reached its scheduled end time: " + eventId);
        }

        List<RaffleEntry> entries = raffleEntryRepository
                .findBySaleEventIdAndStatusOrderByCreatedAtAsc(eventId, RaffleEntryStatus.ENTERED);
        if (!entries.isEmpty()) {
            RaffleDrawRequest request = new RaffleDrawRequest();
            request.setWinnerCount(event.getStockQuantity());
            request.setOperatorId("raffle-lifecycle-worker");
            return draw(eventId, request);
        }

        event.setStatus(SaleEventStatus.ENDED);
        saleEventRepository.save(event);
        RaffleStatusResponse response = status(eventId, null);
        AfterCommitExecutor.run(() -> marketplaceRealtimeService.publishRaffle(
                eventId, "raffle-status", response));
        return response;
    }

    public SseEmitter streamStatus(String eventId) {
        return marketplaceRealtimeService.streamRaffle(eventId, () -> status(eventId, null));
    }

    private SaleEvent requireRaffleEvent(String eventId) {
        SaleEvent event = saleEventRepository.findById(eventId)
                .orElseThrow(() -> new MarketplaceCheckoutException(HttpStatus.NOT_FOUND, "Sale event not found: " + eventId));
        if (event.getSaleType() != SaleType.RAFFLE) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "RAFFLE 이벤트가 아닙니다.");
        }
        return event;
    }

    private SaleEvent requireRaffleEventForUpdate(String eventId) {
        SaleEvent event = saleEventRepository.findByIdForUpdate(eventId)
                .orElseThrow(() -> new MarketplaceCheckoutException(HttpStatus.NOT_FOUND, "Sale event not found: " + eventId));
        if (event.getSaleType() != SaleType.RAFFLE) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "RAFFLE 이벤트가 아닙니다.");
        }
        return event;
    }

    private MarketplaceListing requireActiveListing(SaleEvent event) {
        MarketplaceListing listing = marketplaceListingRepository.findById(event.getListingId())
                .orElseThrow(() -> new MarketplaceCheckoutException(HttpStatus.NOT_FOUND, "Listing not found: " + event.getListingId()));
        if (listing.getStatus() != ListingStatus.ACTIVE) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "Listing is not active.");
        }
        return listing;
    }

    private void validateRaffleOpen(SaleEvent event, MarketplaceListing listing) {
        if (listing.getStatus() != ListingStatus.ACTIVE || event.getStatus() != SaleEventStatus.LIVE) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "응모 가능한 상태가 아닙니다.");
        }
        LocalDateTime now = LocalDateTime.now();
        if (event.getStartsAt() != null && event.getStartsAt().isAfter(now)) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "아직 응모가 시작되지 않았습니다.");
        }
        if (event.getEndsAt() != null && !event.getEndsAt().isAfter(now)) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "응모가 마감되었습니다.");
        }
    }

    private RaffleEntryResponse toEntryResponse(RaffleEntry entry) {
        return RaffleEntryResponse.builder()
                .entryId(entry.getEntryId())
                .saleEventId(entry.getSaleEventId())
                .customerId(entry.getCustomerId())
                .status(entry.getStatus())
                .createdAt(entry.getCreatedAt())
                .build();
    }

    private LocalDateTime checkoutExpiresAt(List<RaffleWinner> winners, RaffleWinner customerWinner) {
        if (customerWinner != null) {
            return customerWinner.getCheckoutExpiresAt();
        }
        return winners.stream()
                .map(RaffleWinner::getCheckoutExpiresAt)
                .filter(value -> value != null)
                .min(LocalDateTime::compareTo)
                .orElse(null);
    }

    private String drawRank(String seed, RaffleEntry entry) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String material = seed + ":" + entry.getEntryId() + ":" + entry.getCustomerId();
            return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to calculate raffle draw rank", e);
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash raffle audit material", e);
        }
    }

    private String secureSeed() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String publicAlias(String customerId) {
        return "participant-" + sha256(defaultText(customerId, "unknown")).substring(0, 8);
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }
}

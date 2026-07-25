package com.example.payment.application.service;

import com.example.payment.domain.model.marketplace.AuctionBid;
import com.example.payment.domain.model.marketplace.AuctionBidStatus;
import com.example.payment.domain.model.marketplace.AuctionSettlement;
import com.example.payment.domain.model.marketplace.AuctionSettlementStatus;
import com.example.payment.domain.model.marketplace.ListingStatus;
import com.example.payment.domain.model.marketplace.MarketplaceListing;
import com.example.payment.domain.model.marketplace.SaleEvent;
import com.example.payment.domain.model.marketplace.SaleEventStatus;
import com.example.payment.domain.model.marketplace.SaleType;
import com.example.payment.domain.repository.AuctionBidRepository;
import com.example.payment.domain.repository.AuctionSettlementRepository;
import com.example.payment.domain.repository.MarketplaceListingRepository;
import com.example.payment.domain.repository.SaleEventRepository;
import com.example.payment.infrastructure.util.AfterCommitExecutor;
import com.example.payment.presentation.dto.request.AuctionBidRequest;
import com.example.payment.presentation.dto.response.AuctionBidResponse;
import com.example.payment.presentation.dto.response.AuctionStatusResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuctionService {

    private final SaleEventRepository saleEventRepository;
    private final MarketplaceListingRepository marketplaceListingRepository;
    private final AuctionBidRepository auctionBidRepository;
    private final AuctionSettlementRepository auctionSettlementRepository;
    private final MarketplaceRealtimeService marketplaceRealtimeService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.auction.anti-sniping-extension-seconds:30}")
    private long antiSnipingExtensionSeconds;

    @Value("${app.marketplace.winner-checkout-ttl-minutes:30}")
    private long winnerCheckoutTtlMinutes;

    @Transactional
    public AuctionBidResponse placeBid(String eventId, AuctionBidRequest request) {
        SaleEvent event = requireAuctionEventForUpdate(eventId);
        requireLiveAuction(event);
        requireActiveListing(event);
        if (auctionSettlementRepository.findBySaleEventId(eventId).isPresent()) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "The auction has already closed.");
        }

        String idempotencyKey = normalizedKey(request.getIdempotencyKey());
        if (idempotencyKey != null) {
            AuctionBid replay = auctionBidRepository
                    .findBySaleEventIdAndCustomerIdAndIdempotencyKey(eventId, request.getCustomerId(), idempotencyKey)
                    .orElse(null);
            if (replay != null) {
                if (replay.getBidAmount().compareTo(request.getBidAmount()) != 0) {
                    throw new IdempotencyConflictException("IDEMPOTENCY_KEY_CONFLICT");
                }
                return toBidResponse(replay);
            }
        }

        AuctionBid currentHighest = auctionBidRepository
                .findFirstBySaleEventIdOrderByBidAmountDescCreatedAtAsc(eventId)
                .orElse(null);
        BigDecimal currentBid = currentHighest != null ? currentHighest.getBidAmount() : event.getPrice();
        BigDecimal increment = event.getMinBidIncrement() != null
                ? event.getMinBidIncrement()
                : new BigDecimal("1000");
        BigDecimal minimumBid = currentBid.add(increment);
        if (request.getBidAmount().compareTo(minimumBid) < 0) {
            throw new MarketplaceCheckoutException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Minimum next bid is " + minimumBid + "."
            );
        }

        if (currentHighest != null) {
            currentHighest.setStatus(AuctionBidStatus.OUTBID);
            auctionBidRepository.save(currentHighest);
        }
        AuctionBid saved = auctionBidRepository.save(AuctionBid.builder()
                .bidId("BID-" + shortId())
                .saleEventId(eventId)
                .customerId(request.getCustomerId())
                .idempotencyKey(idempotencyKey)
                .bidAmount(request.getBidAmount())
                .status(AuctionBidStatus.WINNING)
                .createdAt(LocalDateTime.now())
                .build());

        LocalDateTime extensionBoundary = LocalDateTime.now().plusSeconds(antiSnipingExtensionSeconds);
        if (event.getEndsAt() != null && event.getEndsAt().isBefore(extensionBoundary)) {
            event.setEndsAt(extensionBoundary);
            saleEventRepository.save(event);
        }

        AuctionStatusResponse snapshot = status(eventId);
        AfterCommitExecutor.run(() -> {
            redisTemplate.opsForValue().set(
                    "auction:highest_bid:" + eventId,
                    saved.getBidAmount().toString(),
                    Duration.ofHours(24)
            );
            marketplaceRealtimeService.publishAuction(eventId, "auction-status", snapshot);
            marketplaceRealtimeService.publishAuction(eventId, "bid-placed", snapshot);
        });
        return toBidResponse(saved);
    }

    @Deprecated(forRemoval = true)
    private AuctionBidResponse placeBidLegacy(String eventId, AuctionBidRequest request) {
        // 1. 멱등성 검증 (idempotencyKey가 있는 경우)
        if (request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank()) {
            String idempotencyKey = request.getIdempotencyKey();
            String cachedResponseStr = (String) redisTemplate.opsForValue().get("idempotency:bid:" + idempotencyKey);
            if (cachedResponseStr != null) {
                try {
                    return objectMapper.readValue(cachedResponseStr, AuctionBidResponse.class);
                } catch (Exception e) {
                    // Ignore parsing error and proceed
                }
            }
        }

        // 2. Redis 기반 O(1) Fast-Filter 최고가 입찰액 검증
        SaleEvent event = saleEventRepository.findById(eventId)
                .orElseThrow(() -> new MarketplaceCheckoutException(HttpStatus.NOT_FOUND, "Sale event not found: " + eventId));
        BigDecimal increment = event.getMinBidIncrement() != null ? event.getMinBidIncrement() : new BigDecimal("1000");

        BigDecimal cachedHighest = getOrInitHighestBidCache(eventId, event.getPrice());
        BigDecimal minimumBid = cachedHighest.add(increment);
        if (request.getBidAmount().compareTo(minimumBid) < 0) {
            throw new MarketplaceCheckoutException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "현재 입찰가 기준 최소 입찰 금액은 " + minimumBid + "입니다.");
        }

        // 3. DB 정합성 획득 (Pessimistic lock) 및 실제 처리
        event = requireAuctionEventForUpdate(eventId);
        requireLiveAuction(event);
        requireActiveListing(event);
        if (auctionSettlementRepository.findBySaleEventId(eventId).isPresent()) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "이미 마감된 경매입니다.");
        }

        AuctionBid currentHighest = auctionBidRepository.findFirstBySaleEventIdOrderByBidAmountDescCreatedAtAsc(eventId)
                .orElse(null);
        BigDecimal currentBid = currentHighest != null ? currentHighest.getBidAmount() : event.getPrice();
        minimumBid = currentBid.add(increment);

        // 동시성 레이스 컨디션 방지를 위해 락 획득 후 최종 재확인
        if (request.getBidAmount().compareTo(minimumBid) < 0) {
            // 입찰 실패 시 캐시 최신화
            redisTemplate.opsForValue().set("auction:highest_bid:" + eventId, currentBid.toString(), Duration.ofHours(24));
            throw new MarketplaceCheckoutException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "현재 입찰가 기준 최소 입찰 금액은 " + minimumBid + "입니다.");
        }

        if (currentHighest != null) {
            currentHighest.setStatus(AuctionBidStatus.OUTBID);
            auctionBidRepository.save(currentHighest);
        }

        AuctionBid saved = auctionBidRepository.save(AuctionBid.builder()
                .bidId("BID-" + shortId())
                .saleEventId(eventId)
                .customerId(request.getCustomerId())
                .bidAmount(request.getBidAmount())
                .status(AuctionBidStatus.WINNING)
                .createdAt(LocalDateTime.now())
                .build());

        // Redis 캐시 동기화
        redisTemplate.opsForValue().set("auction:highest_bid:" + eventId, saved.getBidAmount().toString(), Duration.ofHours(24));

        LocalDateTime extensionBoundary = LocalDateTime.now().plusSeconds(antiSnipingExtensionSeconds);
        if (event.getEndsAt() != null && event.getEndsAt().isBefore(extensionBoundary)) {
            event.setEndsAt(extensionBoundary);
            saleEventRepository.save(event);
        }

        AuctionStatusResponse auctionStatus = status(eventId);
        marketplaceRealtimeService.publishAuction(eventId, "auction-status", auctionStatus);
        marketplaceRealtimeService.publishAuction(eventId, "bid-placed", auctionStatus);

        AuctionBidResponse response = toBidResponse(saved);
        cacheBidIdempotencyIfNeeded(request.getIdempotencyKey(), response);

        return response;
    }

    private BigDecimal getOrInitHighestBidCache(String eventId, BigDecimal basePrice) {
        String cacheKey = "auction:highest_bid:" + eventId;
        String val = (String) redisTemplate.opsForValue().get(cacheKey);
        if (val != null) {
            try {
                return new BigDecimal(val);
            } catch (NumberFormatException e) {
                // Ignore and re-query
            }
        }
        // Query DB
        AuctionBid highest = auctionBidRepository.findFirstBySaleEventIdOrderByBidAmountDescCreatedAtAsc(eventId)
                .orElse(null);
        BigDecimal highestAmt = highest != null ? highest.getBidAmount() : basePrice;
        redisTemplate.opsForValue().set(cacheKey, highestAmt.toString(), Duration.ofHours(24));
        return highestAmt;
    }

    private void cacheBidIdempotencyIfNeeded(String idempotencyKey, AuctionBidResponse response) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            try {
                String responseStr = objectMapper.writeValueAsString(response);
                redisTemplate.opsForValue().set("idempotency:bid:" + idempotencyKey, responseStr, Duration.ofMinutes(10));
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    @Transactional(readOnly = true)
    public AuctionStatusResponse status(String eventId) {
        SaleEvent event = requireAuctionEvent(eventId);
        AuctionBid highest = auctionBidRepository.findFirstBySaleEventIdOrderByBidAmountDescCreatedAtAsc(eventId)
                .orElse(null);
        BigDecimal highestBid = highest != null ? highest.getBidAmount() : event.getPrice();
        BigDecimal increment = event.getMinBidIncrement() != null ? event.getMinBidIncrement() : new BigDecimal("1000");
        AuctionSettlement settlement = auctionSettlementRepository.findBySaleEventId(eventId).orElse(null);

        return AuctionStatusResponse.builder()
                .saleEventId(eventId)
                .eventStatus(event.getStatus())
                .highestBid(highestBid)
                .highestBidder(highest != null ? publicAlias(highest.getCustomerId()) : null)
                .minNextBid(highestBid.add(increment))
                .settlementStatus(settlement != null ? settlement.getStatus() : null)
                .closed(isClosed(event, settlement))
                .winnerCustomerId(settlement != null ? publicAlias(settlement.getCustomerId()) : null)
                .secondsRemaining(secondsRemaining(event))
                .streamVersion(streamVersion(event))
                .checkoutExpiresAt(settlement != null ? settlement.getCheckoutExpiresAt() : null)
                .endsAt(event.getEndsAt())
                .history(auctionBidRepository.findTop10BySaleEventIdOrderByBidAmountDescCreatedAtAsc(eventId)
                        .stream()
                        .map(this::toPublicBidResponse)
                        .toList())
                .build();
    }

    @Transactional(readOnly = true)
    public AuctionStatusResponse statusForCustomer(String eventId, String customerId) {
        AuctionStatusResponse response = status(eventId);
        AuctionSettlement settlement = auctionSettlementRepository.findBySaleEventId(eventId).orElse(null);
        response.setCurrentUserWinning(settlement != null
                && settlement.getCustomerId().equals(customerId)
                && settlement.getStatus() == AuctionSettlementStatus.AWAITING_PAYMENT);
        return response;
    }

    @Transactional
    public AuctionStatusResponse close(String eventId) {
        AuctionStatusResponse response = closeInternal(eventId, false);
        AfterCommitExecutor.run(() -> {
            marketplaceRealtimeService.publishAuction(eventId, "auction-status", response);
            marketplaceRealtimeService.publishAuction(eventId, "auction-closed", response);
        });
        return response;
    }

    @Transactional
    public AuctionStatusResponse closeDueAuction(String eventId) {
        AuctionStatusResponse response = closeInternal(eventId, true);
        AfterCommitExecutor.run(() -> {
            marketplaceRealtimeService.publishAuction(eventId, "auction-status", response);
            marketplaceRealtimeService.publishAuction(eventId, "auction-closed", response);
        });
        return response;
    }

    public SseEmitter streamStatus(String eventId) {
        return marketplaceRealtimeService.streamAuction(eventId, () -> status(eventId));
    }

    private AuctionStatusResponse closeInternal(String eventId, boolean allowNoBidClose) {
        SaleEvent event = requireAuctionEventForUpdate(eventId);
        AuctionSettlement existingSettlement = auctionSettlementRepository.findBySaleEventId(eventId).orElse(null);
        if (event.getStatus() == SaleEventStatus.ENDED && existingSettlement != null) {
            return status(eventId);
        }

        AuctionBid highest = auctionBidRepository.findFirstBySaleEventIdOrderByBidAmountDescCreatedAtAsc(eventId)
                .orElse(null);
        if (highest == null) {
            if (!allowNoBidClose) {
                throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "입찰 이력이 없어 경매를 마감할 수 없습니다.");
            }
            event.setStatus(SaleEventStatus.ENDED);
            event.setEndsAt(LocalDateTime.now());
            saleEventRepository.save(event);
            return status(eventId);
        }

        if (existingSettlement == null) {
            auctionSettlementRepository.save(AuctionSettlement.builder()
                    .settlementId("AUCTSET-" + shortId())
                    .saleEventId(eventId)
                    .winningBidId(highest.getBidId())
                    .customerId(highest.getCustomerId())
                    .sellerId(event.getSellerId())
                    .amount(highest.getBidAmount())
                    .status(AuctionSettlementStatus.AWAITING_PAYMENT)
                    .createdAt(LocalDateTime.now())
                    .checkoutExpiresAt(LocalDateTime.now().plusMinutes(winnerCheckoutTtlMinutes))
                    .build());
        }

        event.setStatus(SaleEventStatus.ENDED);
        event.setEndsAt(LocalDateTime.now());
        saleEventRepository.save(event);
        return status(eventId);
    }

    private SaleEvent requireAuctionEvent(String eventId) {
        SaleEvent event = saleEventRepository.findById(eventId)
                .orElseThrow(() -> new MarketplaceCheckoutException(HttpStatus.NOT_FOUND, "Sale event not found: " + eventId));
        if (event.getSaleType() != SaleType.AUCTION) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "AUCTION 이벤트가 아닙니다.");
        }
        return event;
    }

    private SaleEvent requireAuctionEventForUpdate(String eventId) {
        SaleEvent event = saleEventRepository.findByIdForUpdate(eventId)
                .orElseThrow(() -> new MarketplaceCheckoutException(HttpStatus.NOT_FOUND, "Sale event not found: " + eventId));
        if (event.getSaleType() != SaleType.AUCTION) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "AUCTION 이벤트가 아닙니다.");
        }
        return event;
    }

    private void requireLiveAuction(SaleEvent event) {
        LocalDateTime now = LocalDateTime.now();
        if (event.getStatus() != SaleEventStatus.LIVE) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "경매가 진행 중이 아닙니다.");
        }
        if (event.getStartsAt() != null && event.getStartsAt().isAfter(now)) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "경매가 아직 시작되지 않았습니다.");
        }
        if (event.getEndsAt() != null && !event.getEndsAt().isAfter(now)) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "경매가 종료되었습니다.");
        }
    }

    private MarketplaceListing requireActiveListing(SaleEvent event) {
        MarketplaceListing listing = marketplaceListingRepository.findById(event.getListingId())
                .orElseThrow(() -> new MarketplaceCheckoutException(HttpStatus.NOT_FOUND, "Listing not found: " + event.getListingId()));
        if (listing.getStatus() != ListingStatus.ACTIVE) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "Listing is not active.");
        }
        return listing;
    }

    private AuctionBidResponse toBidResponse(AuctionBid bid) {
        return AuctionBidResponse.builder()
                .bidId(bid.getBidId())
                .saleEventId(bid.getSaleEventId())
                .customerId(bid.getCustomerId())
                .bidAmount(bid.getBidAmount())
                .status(bid.getStatus())
                .createdAt(bid.getCreatedAt())
                .build();
    }

    private AuctionBidResponse toPublicBidResponse(AuctionBid bid) {
        return AuctionBidResponse.builder()
                .bidId(bid.getBidId())
                .saleEventId(bid.getSaleEventId())
                .customerId(publicAlias(bid.getCustomerId()))
                .bidAmount(bid.getBidAmount())
                .status(bid.getStatus())
                .createdAt(bid.getCreatedAt())
                .build();
    }

    private String publicAlias(String customerId) {
        return "bidder-" + Integer.toUnsignedString(defaultText(customerId, "unknown").hashCode(), 36).toUpperCase(Locale.ROOT);
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private String normalizedKey(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean isClosed(SaleEvent event, AuctionSettlement settlement) {
        return event.getStatus() == SaleEventStatus.ENDED
                || event.getStatus() == SaleEventStatus.CANCELLED
                || settlement != null;
    }

    private Long secondsRemaining(SaleEvent event) {
        if (event.getEndsAt() == null) {
            return null;
        }
        long seconds = Duration.between(LocalDateTime.now(), event.getEndsAt()).getSeconds();
        return Math.max(0L, seconds);
    }

    private long streamVersion(SaleEvent event) {
        LocalDateTime updatedAt = event.getUpdatedAt() != null ? event.getUpdatedAt() : event.getCreatedAt();
        return updatedAt != null ? Timestamp.valueOf(updatedAt).getTime() : System.currentTimeMillis();
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }
}

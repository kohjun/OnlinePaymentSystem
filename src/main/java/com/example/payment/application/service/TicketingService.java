package com.example.payment.application.service;

import com.example.payment.domain.entity.InventoryReservationRecord;
import com.example.payment.domain.model.marketplace.ListingStatus;
import com.example.payment.domain.model.marketplace.MarketplaceListing;
import com.example.payment.domain.model.marketplace.SaleEvent;
import com.example.payment.domain.model.marketplace.SaleEventStatus;
import com.example.payment.domain.model.marketplace.SaleType;
import com.example.payment.domain.repository.InventoryReservationRecordRepository;
import com.example.payment.domain.repository.MarketplaceListingRepository;
import com.example.payment.domain.repository.SaleEventRepository;
import com.example.payment.domain.repository.TossPaymentIntentRepository;
import com.example.payment.infrastructure.tenancy.TenantContext;
import com.example.payment.presentation.dto.response.TicketSeatHoldResponse;
import com.example.payment.presentation.dto.response.TicketSeatMapResponse;
import com.example.payment.presentation.dto.response.TicketSeatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class TicketingService {

    private static final Collection<String> ACTIVE_RESERVATION_STATUSES = List.of("RESERVED", "CONFIRMED");
    private static final DefaultRedisScript<Long> ACQUIRE_HOLD_SCRIPT = new DefaultRedisScript<>(
            "local currentSeat = redis.call('get', KEYS[2]); "
                    + "if currentSeat and currentSeat ~= ARGV[2] then return -1 end; "
                    + "local owner = redis.call('get', KEYS[1]); "
                    + "if owner and owner ~= ARGV[1] then return 0 end; "
                    + "redis.call('psetex', KEYS[1], ARGV[3], ARGV[1]); "
                    + "redis.call('psetex', KEYS[2], ARGV[3], ARGV[2]); return 1",
            Long.class
    );
    private static final DefaultRedisScript<Long> RELEASE_HOLD_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) ~= ARGV[1] then return 0 end; "
                    + "redis.call('del', KEYS[1]); "
                    + "if redis.call('get', KEYS[2]) == ARGV[2] then redis.call('del', KEYS[2]) end; return 1",
            Long.class
    );
    private static final DefaultRedisScript<Long> EXTEND_HOLD_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) ~= ARGV[1] then return 0 end; "
                    + "redis.call('pexpire', KEYS[1], ARGV[3]); "
                    + "redis.call('psetex', KEYS[2], ARGV[3], ARGV[2]); return 1",
            Long.class
    );

    private final SaleEventRepository saleEventRepository;
    private final MarketplaceListingRepository marketplaceListingRepository;
    private final InventoryReservationRecordRepository reservationRepository;
    private final TossPaymentIntentRepository tossPaymentIntentRepository;
    private final StandbyQueueService standbyQueueService;
    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${app.ticketing.hold-seconds:600}")
    private long holdSeconds;

    @Value("${app.ticketing.max-seats-per-event:500}")
    private int maxSeatsPerEvent;

    @Transactional(readOnly = true)
    public TicketSeatMapResponse getSeats(String eventId, String customerId) {
        TicketEvent ticketEvent = requireTicketEvent(eventId, false);
        List<SeatDefinition> definitions = seatDefinitions(ticketEvent.event());
        List<String> seatIds = definitions.stream().map(SeatDefinition::seatId).toList();
        Map<String, InventoryReservationRecord> reservations = activeReservations(ticketEvent.event(), seatIds);
        List<String> holdKeys = seatIds.stream().map(seatId -> holdKey(eventId, seatId)).toList();
        List<Object> holders = holdKeys.isEmpty()
                ? List.of()
                : redisTemplate.opsForValue().multiGet(holdKeys);

        List<TicketSeatResponse> seats = new ArrayList<>(definitions.size());
        int available = 0;
        int held = 0;
        int sold = 0;
        for (int index = 0; index < definitions.size(); index++) {
            SeatDefinition definition = definitions.get(index);
            InventoryReservationRecord reservation = reservations.get(definition.seatId());
            Object redisHolder = holders != null && index < holders.size() ? holders.get(index) : null;

            String status;
            boolean owned;
            if (reservation != null && "CONFIRMED".equals(reservation.getStatus())) {
                status = "SOLD";
                owned = customerId.equals(reservation.getCustomerId());
                sold++;
            } else if (reservation != null || redisHolder != null) {
                status = "HELD";
                String owner = reservation != null ? reservation.getCustomerId() : redisHolder.toString();
                owned = customerId.equals(owner);
                held++;
            } else {
                status = "AVAILABLE";
                owned = false;
                available++;
            }

            LocalDateTime holdExpiresAt = null;
            if (owned && redisHolder != null) {
                Long ttl = redisTemplate.getExpire(holdKeys.get(index), TimeUnit.SECONDS);
                if (ttl != null && ttl > 0) {
                    holdExpiresAt = LocalDateTime.now().plusSeconds(ttl);
                }
            }
            seats.add(TicketSeatResponse.builder()
                    .seatId(definition.seatId())
                    .section("GENERAL")
                    .rowLabel(definition.rowLabel())
                    .seatNumber(definition.seatNumber())
                    .label(definition.label())
                    .status(status)
                    .ownedByCurrentUser(owned)
                    .holdExpiresAt(holdExpiresAt)
                    .build());
        }

        return TicketSeatMapResponse.builder()
                .saleEventId(eventId)
                .eventStatus(ticketEvent.event().getStatus())
                .totalCount(seats.size())
                .availableCount(available)
                .heldCount(held)
                .soldCount(sold)
                .holdSeconds(holdSeconds)
                .seats(seats)
                .build();
    }

    public TicketSeatHoldResponse hold(String eventId, String seatId, String customerId) {
        TicketEvent ticketEvent = requireTicketEvent(eventId, true);
        validateSeat(ticketEvent.event(), seatId);
        if (!standbyQueueService.hasActiveLease(customerId)) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "ACTIVE_QUEUE_LEASE_REQUIRED");
        }
        if (reservationRepository.findFirstBySeatIdAndStatusIn(seatId, ACTIVE_RESERVATION_STATUSES).isPresent()) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "SEAT_ALREADY_RESERVED");
        }

        String key = holdKey(eventId, seatId);
        String customerKey = customerHoldKey(eventId, customerId);
        Duration duration = Duration.ofSeconds(Math.max(30, holdSeconds));
        Long acquired = redisTemplate.execute(
                ACQUIRE_HOLD_SCRIPT,
                List.of(key, customerKey),
                customerId,
                seatId,
                duration.toMillis()
        );
        if (acquired != null && acquired == -1L) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "CUSTOMER_ALREADY_HOLDS_SEAT");
        }
        if (acquired == null || acquired == 0L) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "SEAT_ALREADY_HELD");
        }

        return TicketSeatHoldResponse.builder()
                .saleEventId(eventId)
                .seatId(seatId)
                .status("HELD")
                .expiresAt(LocalDateTime.now().plus(duration))
                .build();
    }

    public void release(String eventId, String seatId, String customerId) {
        TicketEvent ticketEvent = requireTicketEvent(eventId, false);
        validateSeat(ticketEvent.event(), seatId);
        if (tossPaymentIntentRepository.existsBySaleEventIdAndSeatIdAndCustomerIdAndStatusInAndExpiresAtAfter(
                eventId,
                seatId,
                customerId,
                List.of("READY", "AUTHENTICATED", "PENDING", "UNKNOWN"),
                LocalDateTime.now()
        )) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "SEAT_HOLD_BOUND_TO_PAYMENT_INTENT");
        }
        redisTemplate.execute(
                RELEASE_HOLD_SCRIPT,
                List.of(holdKey(eventId, seatId), customerHoldKey(eventId, customerId)),
                customerId,
                seatId
        );
    }

    public void validateAndExtendCheckoutHold(SaleEvent event,
                                              MarketplaceListing listing,
                                              String customerId,
                                              String seatId,
                                              Integer quantity) {
        if (!isDigitalTicket(listing) || !isDirectTicketEvent(event)) {
            if (hasText(seatId)) {
                throw new MarketplaceCheckoutException(HttpStatus.BAD_REQUEST, "SEAT_NOT_SUPPORTED_FOR_EVENT");
            }
            return;
        }
        if (!hasText(seatId)) {
            throw new MarketplaceCheckoutException(HttpStatus.BAD_REQUEST, "SEAT_ID_REQUIRED");
        }
        if (quantity == null || quantity != 1) {
            throw new MarketplaceCheckoutException(HttpStatus.BAD_REQUEST, "TICKET_QUANTITY_MUST_BE_ONE");
        }
        validateSeat(event, seatId);
        if (reservationRepository.findFirstBySeatIdAndStatusIn(seatId, ACTIVE_RESERVATION_STATUSES).isPresent()) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "SEAT_ALREADY_RESERVED");
        }
        String key = holdKey(event.getSaleEventId(), seatId);
        Duration duration = Duration.ofSeconds(Math.max(30, holdSeconds));
        Long extended = extendHold(event.getSaleEventId(), key, seatId, customerId, duration);
        if (extended == null || extended == 0) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "TICKET_SEAT_HOLD_REQUIRED");
        }
    }

    public void completeCheckout(String eventId, String seatId, String customerId) {
        if (hasText(eventId) && hasText(seatId) && hasText(customerId)) {
            redisTemplate.execute(
                    RELEASE_HOLD_SCRIPT,
                    List.of(holdKey(eventId, seatId), customerHoldKey(eventId, customerId)),
                    customerId,
                    seatId
            );
        }
    }

    public boolean isDigitalTicket(MarketplaceListing listing) {
        return listing != null && "DIGITAL_TICKET".equalsIgnoreCase(listing.getItemCondition());
    }

    private TicketEvent requireTicketEvent(String eventId, boolean requireLive) {
        SaleEvent event = saleEventRepository.findById(eventId)
                .orElseThrow(() -> new MarketplaceCheckoutException(HttpStatus.NOT_FOUND, "Sale event not found: " + eventId));
        MarketplaceListing listing = marketplaceListingRepository.findById(event.getListingId())
                .orElseThrow(() -> new MarketplaceCheckoutException(HttpStatus.NOT_FOUND, "Listing not found: " + event.getListingId()));
        if (listing.getStatus() != ListingStatus.ACTIVE || !isDigitalTicket(listing) || !isDirectTicketEvent(event)) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "TICKETING_NOT_AVAILABLE_FOR_EVENT");
        }
        if (requireLive) {
            LocalDateTime now = LocalDateTime.now();
            if (event.getStatus() != SaleEventStatus.LIVE
                    || event.getStartsAt() != null && event.getStartsAt().isAfter(now)
                    || event.getEndsAt() != null && !event.getEndsAt().isAfter(now)) {
                throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "TICKET_EVENT_NOT_LIVE");
            }
        }
        return new TicketEvent(event, listing);
    }

    private boolean isDirectTicketEvent(SaleEvent event) {
        return event.getSaleType() == SaleType.DROP || event.getSaleType() == SaleType.FIXED_PRICE;
    }

    private List<SeatDefinition> seatDefinitions(SaleEvent event) {
        int count = event.getStockQuantity() != null ? event.getStockQuantity() : 0;
        if (count < 1) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "TICKET_EVENT_HAS_NO_SEATS");
        }
        if (count > maxSeatsPerEvent) {
            throw new MarketplaceCheckoutException(HttpStatus.CONFLICT, "TICKET_EVENT_EXCEEDS_SEAT_MAP_LIMIT");
        }
        List<SeatDefinition> seats = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
            String row = rowLabel((index - 1) / 10);
            int number = (index - 1) % 10 + 1;
            seats.add(new SeatDefinition(
                    event.getSaleEventId() + "-SEAT-" + String.format(Locale.ROOT, "%04d", index),
                    row,
                    number,
                    row + "열 " + number + "번"
            ));
        }
        return seats;
    }

    private void validateSeat(SaleEvent event, String seatId) {
        boolean valid = hasText(seatId) && seatDefinitions(event).stream().anyMatch(seat -> seat.seatId().equals(seatId));
        if (!valid) {
            throw new MarketplaceCheckoutException(HttpStatus.BAD_REQUEST, "INVALID_TICKET_SEAT");
        }
    }

    private Map<String, InventoryReservationRecord> activeReservations(SaleEvent event, List<String> seatIds) {
        Map<String, InventoryReservationRecord> result = new HashMap<>();
        for (InventoryReservationRecord reservation : reservationRepository
                .findByProductIdAndSeatIdInAndStatusIn(event.getProductId(), seatIds, ACTIVE_RESERVATION_STATUSES)) {
            InventoryReservationRecord existing = result.get(reservation.getSeatId());
            if (existing == null || "CONFIRMED".equals(reservation.getStatus())) {
                result.put(reservation.getSeatId(), reservation);
            }
        }
        return result;
    }

    private Long extendHold(String eventId, String key, String seatId, String customerId, Duration duration) {
        return redisTemplate.execute(
                EXTEND_HOLD_SCRIPT,
                List.of(key, customerHoldKey(eventId, customerId)),
                customerId,
                seatId,
                duration.toMillis()
        );
    }

    private String holdKey(String eventId, String seatId) {
        String tenantId = TenantContext.getTenantId();
        String tenant = hasText(tenantId) ? tenantId.trim().toLowerCase(Locale.ROOT).replace(':', '_') : "default";
        return "everysale:ticket:hold:" + tenant + ":" + eventId + ":" + seatId;
    }

    private String customerHoldKey(String eventId, String customerId) {
        String tenantId = TenantContext.getTenantId();
        String tenant = hasText(tenantId) ? tenantId.trim().toLowerCase(Locale.ROOT).replace(':', '_') : "default";
        return "everysale:ticket:customer-hold:" + tenant + ":" + eventId + ":" + customerId;
    }

    private String rowLabel(int zeroBasedRow) {
        StringBuilder label = new StringBuilder();
        int value = zeroBasedRow;
        do {
            label.insert(0, (char) ('A' + value % 26));
            value = value / 26 - 1;
        } while (value >= 0);
        return label.toString();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record TicketEvent(SaleEvent event, MarketplaceListing listing) {
    }

    private record SeatDefinition(String seatId, String rowLabel, int seatNumber, String label) {
    }
}

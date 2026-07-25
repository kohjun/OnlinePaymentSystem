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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TicketingServiceTest {

    private final SaleEventRepository eventRepository = mock(SaleEventRepository.class);
    private final MarketplaceListingRepository listingRepository = mock(MarketplaceListingRepository.class);
    private final InventoryReservationRecordRepository reservationRepository = mock(InventoryReservationRecordRepository.class);
    private final TossPaymentIntentRepository tossPaymentIntentRepository = mock(TossPaymentIntentRepository.class);
    private final StandbyQueueService queueService = mock(StandbyQueueService.class);
    private final RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
    private final ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
    private final TicketingService service = new TicketingService(
            eventRepository,
            listingRepository,
            reservationRepository,
            tossPaymentIntentRepository,
            queueService,
            redisTemplate
    );

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        ReflectionTestUtils.setField(service, "holdSeconds", 600L);
        ReflectionTestUtils.setField(service, "maxSeatsPerEvent", 500);
        TenantContext.set("TENANT-A", "PARTNER-A", "COR-1");
        when(eventRepository.findById("EVT-TICKET")).thenReturn(Optional.of(event()));
        when(listingRepository.findById("LIST-TICKET")).thenReturn(Optional.of(listing()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void holdsAvailableSeatAtomically() {
        String seatId = "EVT-TICKET-SEAT-0001";
        when(queueService.hasActiveLease("CUST-1")).thenReturn(true);
        when(reservationRepository.findFirstBySeatIdAndStatusIn(eq(seatId), any())).thenReturn(Optional.empty());
        when(redisTemplate.execute(
                any(DefaultRedisScript.class), anyList(), eq("CUST-1"), eq(seatId), eq(600000L)
        )).thenReturn(1L);

        TicketSeatHoldResponse response = service.hold("EVT-TICKET", seatId, "CUST-1");

        assertEquals("HELD", response.getStatus());
        assertEquals(seatId, response.getSeatId());
        verify(redisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(List.of(
                        "everysale:ticket:hold:tenant-a:EVT-TICKET:" + seatId,
                        "everysale:ticket:customer-hold:tenant-a:EVT-TICKET:CUST-1"
                )),
                eq("CUST-1"),
                eq(seatId),
                eq(600000L)
        );
    }

    @Test
    void rejectsSeatHeldByAnotherCustomer() {
        String seatId = "EVT-TICKET-SEAT-0001";
        when(queueService.hasActiveLease("CUST-2")).thenReturn(true);
        when(reservationRepository.findFirstBySeatIdAndStatusIn(eq(seatId), any())).thenReturn(Optional.empty());
        when(redisTemplate.execute(
                any(DefaultRedisScript.class), anyList(), eq("CUST-2"), eq(seatId), eq(600000L)
        )).thenReturn(0L);

        MarketplaceCheckoutException error = assertThrows(
                MarketplaceCheckoutException.class,
                () -> service.hold("EVT-TICKET", seatId, "CUST-2")
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertEquals("SEAT_ALREADY_HELD", error.getMessage());
    }

    @Test
    void checkoutRequiresOwnedHoldAndExtendsIt() {
        String seatId = "EVT-TICKET-SEAT-0001";
        when(reservationRepository.findFirstBySeatIdAndStatusIn(eq(seatId), any())).thenReturn(Optional.empty());
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                anyList(),
                eq("CUST-1"),
                eq(seatId),
                eq(600000L)
        )).thenReturn(1L);

        service.validateAndExtendCheckoutHold(event(), listing(), "CUST-1", seatId, 1);

        verify(redisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(List.of(
                        "everysale:ticket:hold:tenant-a:EVT-TICKET:" + seatId,
                        "everysale:ticket:customer-hold:tenant-a:EVT-TICKET:CUST-1"
                )),
                eq("CUST-1"),
                eq(seatId),
                eq(600000L)
        );
    }

    @Test
    void doesNotReleaseSeatWhileActivePaymentIntentOwnsTheHold() {
        String seatId = "EVT-TICKET-SEAT-0001";
        when(tossPaymentIntentRepository.existsBySaleEventIdAndSeatIdAndCustomerIdAndStatusInAndExpiresAtAfter(
                eq("EVT-TICKET"), eq(seatId), eq("CUST-1"), any(), any(LocalDateTime.class)
        )).thenReturn(true);

        MarketplaceCheckoutException error = assertThrows(
                MarketplaceCheckoutException.class,
                () -> service.release("EVT-TICKET", seatId, "CUST-1")
        );

        assertEquals("SEAT_HOLD_BOUND_TO_PAYMENT_INTENT", error.getMessage());
    }

    @Test
    void seatMapUsesConfirmedReservationAsSoldSourceOfTruth() {
        String seatId = "EVT-TICKET-SEAT-0001";
        InventoryReservationRecord sold = InventoryReservationRecord.builder()
                .reservationId("RES-1")
                .productId("PROD-TICKET")
                .customerId("CUST-1")
                .seatId(seatId)
                .quantity(1)
                .status("CONFIRMED")
                .expiresAt(LocalDateTime.now().plusHours(1))
                .build();
        when(reservationRepository.findByProductIdAndSeatIdInAndStatusIn(eq("PROD-TICKET"), any(), any()))
                .thenReturn(List.of(sold));
        when(valueOperations.multiGet(anyList())).thenReturn(Collections.nCopies(10, null));

        TicketSeatMapResponse response = service.getSeats("EVT-TICKET", "CUST-1");

        assertEquals(1, response.getSoldCount());
        assertEquals("SOLD", response.getSeats().get(0).getStatus());
        assertEquals(true, response.getSeats().get(0).isOwnedByCurrentUser());
    }

    private SaleEvent event() {
        return SaleEvent.builder()
                .saleEventId("EVT-TICKET")
                .listingId("LIST-TICKET")
                .sellerId("SELLER-1")
                .productId("PROD-TICKET")
                .saleType(SaleType.DROP)
                .status(SaleEventStatus.LIVE)
                .startsAt(LocalDateTime.now().minusMinutes(1))
                .endsAt(LocalDateTime.now().plusHours(1))
                .stockQuantity(10)
                .build();
    }

    private MarketplaceListing listing() {
        return MarketplaceListing.builder()
                .listingId("LIST-TICKET")
                .sellerId("SELLER-1")
                .productId("PROD-TICKET")
                .itemCondition("DIGITAL_TICKET")
                .status(ListingStatus.ACTIVE)
                .build();
    }
}

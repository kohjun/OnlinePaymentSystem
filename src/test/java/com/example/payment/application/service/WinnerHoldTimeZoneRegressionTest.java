package com.example.payment.application.service;

import com.example.payment.domain.entity.InventoryReservationRecord;
import com.example.payment.domain.entity.TossPaymentIntent;
import com.example.payment.domain.model.inventory.Inventory;
import com.example.payment.domain.repository.InventoryRepository;
import com.example.payment.domain.repository.InventoryReservationRecordRepository;
import com.example.payment.domain.repository.InventorySyncIssueRepository;
import com.example.payment.domain.repository.TossPaymentIntentRepository;
import com.example.payment.infrastructure.config.UtcTimeZoneInitializer;
import com.example.payment.infrastructure.util.ResourceReservationService;
import com.example.payment.presentation.dto.request.TossPaymentConfirmRequest;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 래플·경매 낙찰자 hold는 UTC 기준 만료 시각으로 만들어진다. 서버 기본 시간대가
 * KST이던 시절에는 hold 생성이 TTL을 음수로 계산해 추첨·마감이 실패했고,
 * 결제 인증도 hold를 이미 만료된 것으로 보았다.
 */
class WinnerHoldTimeZoneRegressionTest {

    private final TimeZone original = TimeZone.getDefault();

    private final ResourceReservationService resources = mock(ResourceReservationService.class);
    private final InventoryRepository inventories = mock(InventoryRepository.class);
    private final InventoryReservationRecordRepository reservations = mock(InventoryReservationRecordRepository.class);
    private final TossPaymentIntentRepository intents = mock(TossPaymentIntentRepository.class);

    @BeforeEach
    void startOnKoreanHost() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        UtcTimeZoneInitializer.pin();
    }

    @AfterEach
    void restoreDefaultTimeZone() {
        TimeZone.setDefault(original);
    }

    @Test
    void winnerHoldKeepsItsFullCheckoutWindow() {
        InventoryHoldService holds = new InventoryHoldService(resources, inventories, reservations,
                mock(InventorySyncIssueService.class), mock(InventoryReleasePolicy.class),
                mock(InventorySyncIssueRepository.class), mock(ReservationAuthorizationFence.class));
        // RaffleService·AuctionService.createWinnerOffer와 같은 방식으로 만든 만료 시각.
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(30);
        when(reservations.findByIdForUpdate("WINRES-RAFFLE-1")).thenReturn(Optional.empty());
        when(resources.reserveResource(eq("inventory:PROD-1"), eq(1), any(Duration.class), eq("WINRES-RAFFLE-1")))
                .thenReturn(true);
        when(inventories.findByIdForUpdate("PROD-1")).thenReturn(Optional.of(Inventory.builder()
                .productId("PROD-1").totalQuantity(1).availableQuantity(1).reservedQuantity(0).build()));
        when(reservations.saveAndFlush(any(InventoryReservationRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        InventoryReservationRecord hold = holds.hold("WINRES-RAFFLE-1", "PROD-1", "CUST-1", 1,
                expiresAt, "RAFFLE_WINNER", "RAFFLE_WINNER", "RFWIN-1");

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(resources).reserveResource(eq("inventory:PROD-1"), eq(1), ttl.capture(), eq("WINRES-RAFFLE-1"));
        assertTrue(ttl.getValue().compareTo(Duration.ofMinutes(29)) > 0
                && ttl.getValue().compareTo(Duration.ofMinutes(30)) <= 0, "hold TTL: " + ttl.getValue());
        assertEquals("RESERVED", hold.getStatus());
    }

    @Test
    void winnerCheckoutAuthenticatesAgainstUtcHoldExpiry() {
        TossIntentStateWriter writer = new TossIntentStateWriter(intents, reservations, new ObjectMapper());
        when(intents.findHoldId("TOSS-INTENT-1")).thenReturn(Optional.of("WINRES-RAFFLE-1"));
        when(reservations.findByIdForUpdate("WINRES-RAFFLE-1")).thenReturn(Optional.of(InventoryReservationRecord.builder()
                .reservationId("WINRES-RAFFLE-1")
                .status("RESERVED")
                .expiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(30))
                .build()));
        when(intents.findByIdForUpdate("TOSS-INTENT-1")).thenReturn(Optional.of(TossPaymentIntent.builder()
                .intentId("TOSS-INTENT-1")
                .orderId("ORD-1")
                .amount(new BigDecimal("10000.00"))
                .status("READY")
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .build()));
        when(intents.saveAndFlush(any(TossPaymentIntent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TossPaymentIntent authenticated = writer.authenticate(TossPaymentConfirmRequest.builder()
                .intentId("TOSS-INTENT-1")
                .orderId("ORD-1")
                .paymentKey("pay_winner")
                .amount(new BigDecimal("10000.00"))
                .build());

        assertEquals("AUTHENTICATED", authenticated.getStatus());
    }
}

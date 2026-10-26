package com.example.payment.application.service;

import com.example.payment.domain.model.marketplace.MarketplaceNotification;
import com.example.payment.domain.repository.MarketplaceNotificationRepository;
import com.example.payment.infrastructure.security.EverySalePrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketplaceNotificationServiceTest {

    private final MarketplaceNotificationRepository repository = mock(MarketplaceNotificationRepository.class);
    private final MarketplaceNotificationWriter writer = mock(MarketplaceNotificationWriter.class);
    private final MarketplaceNotificationService service = new MarketplaceNotificationService(repository, writer);

    @Test
    void displayTimeShowsUtcValueInKoreanTime() {
        assertEquals("2026-10-25 10:30 (KST)",
                MarketplaceNotificationService.displayTime(LocalDateTime.of(2026, 10, 25, 1, 30)));
    }

    @Test
    void duplicateIdempotencyKeyDoesNotCreateAnotherNotification() {
        when(repository.existsByIdempotencyKey("order:1:paid")).thenReturn(true);

        service.notifyCustomer(
                "CUST-1", "PAYMENT_COMPLETED", "결제 완료", "주문이 접수되었습니다.",
                "MARKETPLACE_ORDER", "MORD-1", "order:1:paid");

        verify(repository, never()).save(any());
    }

    @Test
    void createsNotificationWithAtomicInsertContract() {
        when(repository.existsByIdempotencyKey("order:3:paid")).thenReturn(false);

        service.notifyCustomer(
                "CUST-1", "PAYMENT_COMPLETED", "결제 완료", "주문이 접수되었습니다.",
                "MARKETPLACE_ORDER", "MORD-3", "order:3:paid");

        verify(writer).insertIfAbsent(
                any(), eq(null), eq("CUST-1"), eq(null), eq("PAYMENT_COMPLETED"),
                eq("결제 완료"), eq("주문이 접수되었습니다."), eq("/app/?view=orders&orderId=MORD-3"),
                eq("MARKETPLACE_ORDER"), eq("MORD-3"), eq("order:3:paid"), any());
    }

    @Test
    void inboxCombinesPrincipalRecipientScopesAndReturnsUnreadCount() {
        MarketplaceNotification notification = MarketplaceNotification.builder()
                .notificationId("NTF-1")
                .recipientCustomerId("CUST-1")
                .notificationType("PAYMENT_COMPLETED")
                .title("결제 완료")
                .message("주문이 접수되었습니다.")
                .idempotencyKey("order:1:paid")
                .createdAt(LocalDateTime.now())
                .build();
        when(repository.findForRecipient(eq("USER-1"), eq("CUST-1"), eq("SELLER-1"), any(Pageable.class)))
                .thenReturn(List.of(notification));
        when(repository.countUnreadForRecipient("USER-1", "CUST-1", "SELLER-1")).thenReturn(1L);

        var inbox = service.getInbox(principal(), 0, 30);

        assertEquals(1, inbox.getNotifications().size());
        assertEquals(1, inbox.getUnreadCount());
        assertEquals("NTF-1", inbox.getNotifications().get(0).getNotificationId());
    }

    @Test
    void cannotReadAnotherCustomersNotification() {
        MarketplaceNotification notification = MarketplaceNotification.builder()
                .notificationId("NTF-2")
                .recipientCustomerId("CUST-OTHER")
                .notificationType("NEW_ORDER")
                .title("새 주문")
                .message("새 주문입니다.")
                .idempotencyKey("order:2:new")
                .createdAt(LocalDateTime.now())
                .build();
        when(repository.findById("NTF-2")).thenReturn(Optional.of(notification));

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> service.markRead(principal(), "NTF-2"));

        assertEquals(404, failure.getStatusCode().value());
        verify(repository, never()).save(any());
    }

    private EverySalePrincipal principal() {
        return new EverySalePrincipal("USER-1", "CUST-1", "SELLER-1", Set.of("CUSTOMER", "SELLER"));
    }
}

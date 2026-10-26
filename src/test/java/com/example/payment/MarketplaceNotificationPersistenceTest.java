package com.example.payment;

import com.example.payment.application.service.MarketplaceNotificationService;
import com.example.payment.infrastructure.security.EverySalePrincipal;
import com.example.payment.infrastructure.util.ResourceReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@Transactional
class MarketplaceNotificationPersistenceTest {

    @Autowired
    private MarketplaceNotificationService notificationService;

    @MockBean
    private ResourceReservationService resourceReservationService;

    @Test
    void atomicNotificationInsertKeepsOneRowForIdempotentReplay() {
        String suffix = UUID.randomUUID().toString();
        String key = "persistence:" + suffix;
        String aggregateId = "MORD-" + suffix;

        notificationService.notifyCustomer(
                "CUST-PERSISTENCE", "PAYMENT_COMPLETED", "결제 완료", "주문 접수",
                "MARKETPLACE_ORDER", aggregateId, key);
        notificationService.notifyCustomer(
                "CUST-PERSISTENCE", "PAYMENT_COMPLETED", "결제 완료", "주문 접수",
                "MARKETPLACE_ORDER", aggregateId, key);

        var inbox = notificationService.getInbox(
                new EverySalePrincipal("USER-PERSISTENCE", "CUST-PERSISTENCE", null, Set.of("CUSTOMER")),
                0,
                100
        );
        long matchingRows = inbox.getNotifications().stream()
                .filter(notification -> aggregateId.equals(notification.getAggregateId()))
                .count();
        assertEquals(1, matchingRows);
    }
}

package com.example.payment.application.service;

import com.example.payment.domain.model.marketplace.MarketplaceOrderStatus;
import com.example.payment.domain.model.marketplace.SaleEvent;
import com.example.payment.domain.model.marketplace.SellerProfile;
import com.example.payment.domain.repository.MarketplaceOrderRepository;
import com.example.payment.domain.repository.SellerProfileRepository;
import com.example.payment.domain.repository.TossPaymentIntentRepository;
import com.example.payment.infrastructure.security.CommerceActor;
import com.example.payment.infrastructure.security.SecurityAuditService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommercePolicyServiceTest {

    private final SellerProfileRepository sellerRepository = mock(SellerProfileRepository.class);
    private final MarketplaceOrderRepository orderRepository = mock(MarketplaceOrderRepository.class);
    private final TossPaymentIntentRepository intentRepository = mock(TossPaymentIntentRepository.class);
    private final SecurityAuditService auditService = mock(SecurityAuditService.class);
    private final CommercePolicyService service = new CommercePolicyService(
            sellerRepository, orderRepository, intentRepository, auditService);

    @Test
    void rejectsSellerOwnerCustomerAsSelfTrade() {
        SaleEvent event = event(1);
        when(sellerRepository.findById("SELLER-1")).thenReturn(Optional.of(SellerProfile.builder()
                .sellerId("SELLER-1")
                .displayName("Seller")
                .ownerUserId("USER-SELLER")
                .ownerCustomerId("CUST-SELLER")
                .build()));

        MarketplaceCheckoutException failure = assertThrows(MarketplaceCheckoutException.class,
                () -> service.requireTradeAllowed(
                        event, new CommerceActor("USER-BUYER", "CUST-SELLER"), "CHECKOUT"));

        assertEquals(HttpStatus.FORBIDDEN, failure.getStatus());
        assertEquals("SELF_TRADE_FORBIDDEN", failure.getCode());
        verify(auditService).recordDenied(
                eq("SELF_TRADE_CHECKOUT"), eq("SALE_EVENT"), eq("EVT-1"), any());
    }

    @Test
    void countsCompletedOrdersAgainstCustomerLimit() {
        SaleEvent event = event(1);
        when(orderRepository.sumCompletedQuantityForCustomer(eq("EVT-1"), eq("CUST-1"), any()))
                .thenReturn(1L);

        MarketplaceCheckoutException failure = assertThrows(MarketplaceCheckoutException.class,
                () -> service.requireDirectPurchaseCapacity(
                        event, new CommerceActor("USER-1", "CUST-1"), 1));

        assertEquals("PURCHASE_LIMIT_EXCEEDED", failure.getCode());
    }

    @Test
    void rejectsSecondActiveIntentForSameCustomerAndEvent() {
        SaleEvent event = event(2);
        when(intentRepository.countBySaleEventIdAndCustomerIdAndStatusInAndExpiresAtAfter(
                eq("EVT-1"), eq("CUST-1"), any(), any(LocalDateTime.class)))
                .thenReturn(1L);

        MarketplaceCheckoutException failure = assertThrows(MarketplaceCheckoutException.class,
                () -> service.requireDirectPurchaseCapacity(
                        event, new CommerceActor("USER-1", "CUST-1"), 1));

        assertEquals("CHECKOUT_ALREADY_IN_PROGRESS", failure.getCode());
    }

    private SaleEvent event(int limit) {
        return SaleEvent.builder()
                .saleEventId("EVT-1")
                .sellerId("SELLER-1")
                .stockQuantity(10)
                .maxQuantityPerCustomer(limit)
                .build();
    }
}

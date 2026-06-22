package com.example.payment.application.service;

import com.example.payment.domain.model.marketplace.FulfillmentStatus;
import com.example.payment.domain.model.marketplace.MarketplaceCheckoutType;
import com.example.payment.domain.model.marketplace.MarketplaceListing;
import com.example.payment.domain.model.marketplace.MarketplaceOrder;
import com.example.payment.domain.model.marketplace.MarketplaceOrderStatus;
import com.example.payment.domain.model.marketplace.SaleEvent;
import com.example.payment.domain.model.marketplace.SaleEventStatus;
import com.example.payment.domain.model.marketplace.SaleType;
import com.example.payment.domain.repository.MarketplaceOrderRepository;
import com.example.payment.presentation.dto.request.CompleteReservationRequest;
import com.example.payment.presentation.dto.request.MarketplaceCheckoutRequest;
import com.example.payment.presentation.dto.response.CompleteReservationResponse;
import com.example.payment.presentation.dto.response.MarketplaceOrderResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketplaceOrderServiceTest {

    private final MarketplaceOrderRepository repository = mock(MarketplaceOrderRepository.class);
    private final SellerPayoutService sellerPayoutService = mock(SellerPayoutService.class);
    private final MarketplaceOrderService service = new MarketplaceOrderService(repository, sellerPayoutService);

    @Test
    void recordsSuccessfulDirectCheckoutAsPaidFulfillmentReadyOrder() {
        when(repository.findByOrderId("ORD-1")).thenReturn(Optional.empty());
        when(repository.save(any(MarketplaceOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.recordCheckout(
                event(SaleType.DROP),
                listing(),
                checkoutRequest(),
                CompleteReservationResponse.success(
                        "RES-1", "ORD-1", "PAY-1", "TX-1",
                        "PROD-1", 1, new BigDecimal("59000"), "KRW"
                ),
                MarketplaceCheckoutType.DIRECT,
                "EVT-1",
                new BigDecimal("59000")
        );

        ArgumentCaptor<MarketplaceOrder> captor = ArgumentCaptor.forClass(MarketplaceOrder.class);
        verify(repository).save(captor.capture());
        assertEquals("EVT-1", captor.getValue().getSaleEventId());
        assertEquals("SELLER-1", captor.getValue().getSellerId());
        assertEquals("CUST-1", captor.getValue().getCustomerId());
        assertEquals(MarketplaceOrderStatus.PAID, captor.getValue().getStatus());
        assertEquals(FulfillmentStatus.READY_TO_FULFILL, captor.getValue().getFulfillmentStatus());
        assertEquals("ORD-1", captor.getValue().getOrderId());
        verify(sellerPayoutService).createHeldPayout(
                eq("SELLER-1"),
                eq("MARKETPLACE_ORDER"),
                any(),
                eq(new BigDecimal("59000"))
        );
    }

    @Test
    void duplicateCheckoutUpdatesExistingMarketplaceOrderInsteadOfCreatingNewOne() {
        MarketplaceOrder existing = MarketplaceOrder.builder()
                .marketplaceOrderId("MORD-1")
                .saleEventId("EVT-1")
                .listingId("LIST-1")
                .sellerId("SELLER-1")
                .customerId("CUST-1")
                .saleType(SaleType.DROP)
                .checkoutType(MarketplaceCheckoutType.DIRECT)
                .status(MarketplaceOrderStatus.PENDING)
                .fulfillmentStatus(FulfillmentStatus.NOT_READY)
                .productId("PROD-1")
                .quantity(1)
                .amount(new BigDecimal("59000"))
                .currency("KRW")
                .orderId("ORD-1")
                .createdAt(LocalDateTime.now())
                .build();
        when(repository.findByOrderId("ORD-1")).thenReturn(Optional.of(existing));
        when(repository.save(any(MarketplaceOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.recordCheckout(
                event(SaleType.DROP),
                listing(),
                checkoutRequest(),
                CompleteReservationResponse.success(
                        "RES-1", "ORD-1", "PAY-1", "TX-1",
                        "PROD-1", 1, new BigDecimal("59000"), "KRW"
                ),
                MarketplaceCheckoutType.DIRECT,
                "EVT-1",
                new BigDecimal("59000")
        );

        ArgumentCaptor<MarketplaceOrder> captor = ArgumentCaptor.forClass(MarketplaceOrder.class);
        verify(repository).save(captor.capture());
        assertEquals("MORD-1", captor.getValue().getMarketplaceOrderId());
        assertEquals(MarketplaceOrderStatus.PAID, captor.getValue().getStatus());
    }

    @Test
    void sellerCanMovePaidOrderToShipped() {
        MarketplaceOrder order = paidOrder();
        when(repository.findByMarketplaceOrderIdAndSellerId("MORD-1", "SELLER-1")).thenReturn(Optional.of(order));
        when(repository.save(any(MarketplaceOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MarketplaceOrderResponse response = service.updateFulfillment(
                "SELLER-1",
                "MORD-1",
                FulfillmentStatus.SHIPPED
        );

        assertEquals(FulfillmentStatus.SHIPPED, response.getFulfillmentStatus());
    }

    @Test
    void pendingOrderCannotBeFulfilledBeforePaymentCompletes() {
        MarketplaceOrder order = paidOrder();
        order.setStatus(MarketplaceOrderStatus.PENDING);
        order.setFulfillmentStatus(FulfillmentStatus.NOT_READY);
        when(repository.findByMarketplaceOrderIdAndSellerId("MORD-1", "SELLER-1")).thenReturn(Optional.of(order));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.updateFulfillment("SELLER-1", "MORD-1", FulfillmentStatus.SHIPPED));

        assertEquals("Only paid marketplace orders can be fulfilled.", ex.getMessage());
    }

    private SaleEvent event(SaleType saleType) {
        return SaleEvent.builder()
                .saleEventId("EVT-1")
                .listingId("LIST-1")
                .sellerId("SELLER-1")
                .productId("PROD-1")
                .saleType(saleType)
                .status(SaleEventStatus.LIVE)
                .startsAt(LocalDateTime.now().minusMinutes(5))
                .price(new BigDecimal("59000"))
                .stockQuantity(10)
                .build();
    }

    private MarketplaceListing listing() {
        return MarketplaceListing.builder()
                .listingId("LIST-1")
                .sellerId("SELLER-1")
                .productId("PROD-1")
                .title("Drop item")
                .build();
    }

    private MarketplaceCheckoutRequest checkoutRequest() {
        MarketplaceCheckoutRequest request = new MarketplaceCheckoutRequest();
        request.setCustomerId("CUST-1");
        request.setQuantity(1);
        request.setPaymentInfo(CompleteReservationRequest.PaymentInfo.builder()
                .amount(new BigDecimal("1"))
                .currency("KRW")
                .paymentMethod("CREDIT_CARD")
                .build());
        return request;
    }

    private MarketplaceOrder paidOrder() {
        return MarketplaceOrder.builder()
                .marketplaceOrderId("MORD-1")
                .saleEventId("EVT-1")
                .listingId("LIST-1")
                .sellerId("SELLER-1")
                .customerId("CUST-1")
                .saleType(SaleType.DROP)
                .checkoutType(MarketplaceCheckoutType.DIRECT)
                .status(MarketplaceOrderStatus.PAID)
                .fulfillmentStatus(FulfillmentStatus.READY_TO_FULFILL)
                .productId("PROD-1")
                .quantity(1)
                .amount(new BigDecimal("59000"))
                .currency("KRW")
                .orderId("ORD-1")
                .createdAt(LocalDateTime.now())
                .build();
    }
}

package com.example.payment.application.service;

import com.example.payment.domain.model.marketplace.FulfillmentStatus;
import com.example.payment.domain.model.marketplace.DisputeResolution;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketplaceOrderServiceTest {

    private final MarketplaceOrderRepository repository = mock(MarketplaceOrderRepository.class);
    private final SellerPayoutService sellerPayoutService = mock(SellerPayoutService.class);
    private final PaymentProcessingService paymentProcessingService = mock(PaymentProcessingService.class);
    private final MarketplaceOrderService service = new MarketplaceOrderService(
            repository,
            sellerPayoutService,
            paymentProcessingService
    );

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
    void recordsDigitalTicketSeatAsIssuedWithoutShippingFulfillment() {
        when(repository.findByOrderId("ORD-1")).thenReturn(Optional.empty());
        when(repository.save(any(MarketplaceOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
        MarketplaceCheckoutRequest request = checkoutRequest();
        request.setSeatId("EVT-1-SEAT-0001");
        MarketplaceListing ticketListing = listing();
        ticketListing.setItemCondition("DIGITAL_TICKET");

        service.recordCheckout(
                event(SaleType.DROP),
                ticketListing,
                request,
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
        assertEquals("EVT-1-SEAT-0001", captor.getValue().getSeatId());
        assertEquals(FulfillmentStatus.DELIVERED, captor.getValue().getFulfillmentStatus());
        assertEquals(true, captor.getValue().getFulfilledAt() != null);
    }

    @Test
    void recordsShippingSnapshotWithSuccessfulCheckout() {
        MarketplaceCheckoutRequest request = checkoutRequest();
        request.setShippingInfo(CompleteReservationRequest.ShippingInfo.builder()
                .addressId("ADDR-1")
                .recipientName("홍길동")
                .postalCode("04524")
                .address("서울 중구 세종대로 110 10층")
                .method("PARCEL")
                .contactPhone("010-0000-0000")
                .specialInstructions("문 앞")
                .build());
        when(repository.findByOrderId("ORD-1")).thenReturn(Optional.empty());
        when(repository.save(any(MarketplaceOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.recordCheckout(
                event(SaleType.DROP),
                listing(),
                request,
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
        assertEquals("ADDR-1", captor.getValue().getShippingAddressId());
        assertEquals("홍길동", captor.getValue().getShippingRecipientName());
        assertEquals("04524", captor.getValue().getShippingPostalCode());
        assertEquals("서울 중구 세종대로 110 10층", captor.getValue().getShippingAddress());
        assertEquals("010-0000-0000", captor.getValue().getShippingContactPhone());
    }

    @Test
    void sellerCanMovePaidOrderToShipped() {
        MarketplaceOrder order = paidOrder();
        when(repository.findByMarketplaceOrderIdAndSellerId("MORD-1", "SELLER-1")).thenReturn(Optional.of(order));
        when(repository.save(any(MarketplaceOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MarketplaceOrderResponse response = service.updateFulfillment(
                "SELLER-1",
                "MORD-1",
                FulfillmentStatus.SHIPPED,
                "CJ대한통운",
                "1234567890"
        );

        assertEquals(FulfillmentStatus.SHIPPED, response.getFulfillmentStatus());
        assertEquals("CJ대한통운", response.getTrackingCarrier());
        assertEquals("1234567890", response.getTrackingNumber());
    }

    @Test
    void shippingRequiresTrackingInformation() {
        MarketplaceOrder order = paidOrder();
        when(repository.findByMarketplaceOrderIdAndSellerId("MORD-1", "SELLER-1")).thenReturn(Optional.of(order));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.updateFulfillment("SELLER-1", "MORD-1", FulfillmentStatus.SHIPPED));

        assertEquals("Tracking carrier and tracking number are required when shipping a marketplace order.", ex.getMessage());
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

    @Test
    void buyerConfirmationMarksOrderDeliveredAndPayoutReady() {
        MarketplaceOrder order = paidOrder();
        order.setFulfillmentStatus(FulfillmentStatus.SHIPPED);
        when(repository.findById("MORD-1")).thenReturn(Optional.of(order));
        when(repository.save(any(MarketplaceOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MarketplaceOrderResponse response = service.confirmDelivery("CUST-1", "MORD-1");

        assertEquals(FulfillmentStatus.DELIVERED, response.getFulfillmentStatus());
        verify(sellerPayoutService).markReadyForRelease("MARKETPLACE_ORDER", "MORD-1");
    }

    @Test
    void buyerConfirmationRejectsWrongCustomer() {
        MarketplaceOrder order = paidOrder();
        order.setFulfillmentStatus(FulfillmentStatus.SHIPPED);
        when(repository.findById("MORD-1")).thenReturn(Optional.of(order));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.confirmDelivery("CUST-OTHER", "MORD-1"));

        assertEquals("Marketplace order does not belong to customer: CUST-OTHER", ex.getMessage());
        verify(sellerPayoutService, never()).markReadyForRelease(any(), any());
    }

    @Test
    void buyerDisputeMarksPayoutDisputedAndBlocksFulfillment() {
        MarketplaceOrder order = paidOrder();
        order.setFulfillmentStatus(FulfillmentStatus.SHIPPED);
        when(repository.findById("MORD-1")).thenReturn(Optional.of(order));
        when(repository.save(any(MarketplaceOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MarketplaceOrderResponse response = service.openDispute("CUST-1", "MORD-1", "Box arrived empty.");

        assertEquals("Box arrived empty.", response.getDisputeReason());
        verify(sellerPayoutService).markDisputed("MARKETPLACE_ORDER", "MORD-1");

        when(repository.findByMarketplaceOrderIdAndSellerId("MORD-1", "SELLER-1")).thenReturn(Optional.of(order));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.updateFulfillment("SELLER-1", "MORD-1", FulfillmentStatus.DELIVERED));
        assertEquals("Disputed marketplace orders cannot be fulfilled until the dispute is resolved.", ex.getMessage());
    }

    @Test
    void adminResolvesDisputeInSellerFavorAndMarksPayoutReady() {
        MarketplaceOrder order = paidOrder();
        order.setFulfillmentStatus(FulfillmentStatus.SHIPPED);
        order.setDisputedAt(LocalDateTime.now());
        order.setDisputeReason("배송 지연");
        when(repository.findById("MORD-1")).thenReturn(Optional.of(order));
        when(repository.save(any(MarketplaceOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MarketplaceOrderResponse response = service.resolveDispute(
                "ops-1",
                "MORD-1",
                DisputeResolution.PAYOUT_READY,
                "배송 완료 증빙 확인"
        );

        assertEquals(DisputeResolution.PAYOUT_READY, response.getDisputeResolution());
        assertEquals(FulfillmentStatus.DELIVERED, response.getFulfillmentStatus());
        verify(sellerPayoutService).markReadyForRelease("MARKETPLACE_ORDER", "MORD-1");
    }

    @Test
    void adminResolvesDisputeInBuyerFavorAndCancelsPayout() {
        MarketplaceOrder order = paidOrder();
        order.setFulfillmentStatus(FulfillmentStatus.SHIPPED);
        order.setDisputedAt(LocalDateTime.now());
        order.setDisputeReason("빈 박스 수령");
        when(repository.findById("MORD-1")).thenReturn(Optional.of(order));
        when(repository.save(any(MarketplaceOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MarketplaceOrderResponse response = service.resolveDispute(
                "ops-1",
                "MORD-1",
                DisputeResolution.PAYOUT_CANCELLED,
                "구매자 증빙 인정"
        );

        assertEquals(DisputeResolution.PAYOUT_CANCELLED, response.getDisputeResolution());
        assertEquals(MarketplaceOrderStatus.CANCELLED, response.getStatus());
        assertEquals(FulfillmentStatus.CANCELLED, response.getFulfillmentStatus());
        verify(sellerPayoutService).markCancelled("MARKETPLACE_ORDER", "MORD-1");
    }

    @Test
    void adminResolvesDisputeWithBuyerRefundAndCancelsPayoutAfterRefundSucceeds() {
        MarketplaceOrder order = paidOrder();
        order.setFulfillmentStatus(FulfillmentStatus.SHIPPED);
        order.setDisputedAt(LocalDateTime.now());
        order.setDisputeReason("Item was not delivered.");
        when(repository.findById("MORD-1")).thenReturn(Optional.of(order));
        when(repository.save(any(MarketplaceOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentProcessingService.refundPaymentWithResult(
                eq("PAY-1"),
                eq("dispute-MORD-1"),
                eq("C2C dispute refund: Buyer evidence accepted")
        )).thenReturn(new PaymentProcessingService.RefundResult(
                true,
                "REFUND_SUCCEEDED",
                "Payment refunded successfully.",
                null
        ));

        MarketplaceOrderResponse response = service.resolveDispute(
                "ops-1",
                "MORD-1",
                DisputeResolution.BUYER_REFUND,
                "Buyer evidence accepted"
        );

        assertEquals(DisputeResolution.BUYER_REFUND, response.getDisputeResolution());
        assertEquals(MarketplaceOrderStatus.REFUNDED, response.getStatus());
        assertEquals(FulfillmentStatus.CANCELLED, response.getFulfillmentStatus());
        verify(paymentProcessingService).refundPaymentWithResult(
                "PAY-1",
                "dispute-MORD-1",
                "C2C dispute refund: Buyer evidence accepted"
        );
        verify(sellerPayoutService).markCancelled("MARKETPLACE_ORDER", "MORD-1");
    }

    @Test
    void buyerRefundDisputeKeepsDisputeOpenWhenRefundFails() {
        MarketplaceOrder order = paidOrder();
        order.setFulfillmentStatus(FulfillmentStatus.SHIPPED);
        order.setDisputedAt(LocalDateTime.now());
        order.setDisputeReason("Item was not delivered.");
        when(repository.findById("MORD-1")).thenReturn(Optional.of(order));
        when(repository.save(any(MarketplaceOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentProcessingService.refundPaymentWithResult(any(), any(), any())).thenReturn(
                new PaymentProcessingService.RefundResult(
                        false,
                        "REFUND_FAILED",
                        "Payment refund failed.",
                        null
                )
        );

        MarketplaceOrderResponse response = service.resolveDispute(
                "ops-1",
                "MORD-1",
                DisputeResolution.BUYER_REFUND,
                "Buyer evidence accepted"
        );

        assertEquals(MarketplaceOrderStatus.REFUND_FAILED, response.getStatus());
        assertEquals(null, response.getDisputeResolution());
        assertEquals(null, response.getDisputeResolvedAt());
        verify(sellerPayoutService, never()).markCancelled(any(), any());
    }

    @Test
    void providerFullRefundSyncMarksMarketplaceOrderRefundedAndCancelsPayout() {
        MarketplaceOrder order = paidOrder();
        when(repository.findByPaymentId("PAY-1")).thenReturn(Optional.of(order));
        when(repository.save(any(MarketplaceOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MarketplaceOrderResponse response = service.syncProviderRefundStatus("PAY-1", "REFUNDED").orElseThrow();

        assertEquals(MarketplaceOrderStatus.REFUNDED, response.getStatus());
        assertEquals(FulfillmentStatus.CANCELLED, response.getFulfillmentStatus());
        assertEquals(DisputeResolution.BUYER_REFUND, response.getDisputeResolution());
        verify(sellerPayoutService).applyProviderRefundStatus("MARKETPLACE_ORDER", "MORD-1", false);
    }

    @Test
    void providerPartialRefundSyncMarksMarketplaceOrderPartiallyRefundedAndDisputed() {
        MarketplaceOrder order = paidOrder();
        when(repository.findByPaymentId("PAY-1")).thenReturn(Optional.of(order));
        when(repository.save(any(MarketplaceOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MarketplaceOrderResponse response = service.syncProviderRefundStatus("PAY-1", "PARTIALLY_REFUNDED").orElseThrow();

        assertEquals(MarketplaceOrderStatus.PARTIALLY_REFUNDED, response.getStatus());
        assertEquals("Provider partial refund received; operations review required.", response.getDisputeReason());
        verify(sellerPayoutService).applyProviderRefundStatus("MARKETPLACE_ORDER", "MORD-1", true);
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
                .paymentId("PAY-1")
                .createdAt(LocalDateTime.now())
                .build();
    }
}

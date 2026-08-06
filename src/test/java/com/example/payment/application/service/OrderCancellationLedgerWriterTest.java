package com.example.payment.application.service;

import com.example.payment.domain.entity.RefundRecord;
import com.example.payment.domain.model.marketplace.FulfillmentStatus;
import com.example.payment.domain.model.marketplace.MarketplaceOrder;
import com.example.payment.domain.model.marketplace.MarketplaceOrderCancellation;
import com.example.payment.domain.model.marketplace.MarketplaceOrderStatus;
import com.example.payment.domain.model.marketplace.OrderCancellationStatus;
import com.example.payment.domain.repository.MarketplaceOrderCancellationRepository;
import com.example.payment.domain.repository.MarketplaceOrderRepository;
import com.example.payment.domain.repository.SellerProfileRepository;
import com.example.payment.infrastructure.messaging.outbox.OutboxEventService;
import com.example.payment.infrastructure.security.CommerceActor;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderCancellationLedgerWriterTest {

    private final MarketplaceOrderRepository orderRepository = mock(MarketplaceOrderRepository.class);
    private final MarketplaceOrderCancellationRepository cancellationRepository =
            mock(MarketplaceOrderCancellationRepository.class);
    private final SellerProfileRepository sellerRepository = mock(SellerProfileRepository.class);
    private final SellerPayoutService payoutService = mock(SellerPayoutService.class);
    private final InventoryRestorationService restorationService = mock(InventoryRestorationService.class);
    private final OutboxEventService outboxEventService = mock(OutboxEventService.class);
    private final OrderCancellationLedgerWriter writer = new OrderCancellationLedgerWriter(
            orderRepository,
            cancellationRepository,
            sellerRepository,
            payoutService,
            restorationService,
            outboxEventService
    );

    @Test
    void refundSuccessCancelsOrderPayoutAndRestoresInventory() {
        MarketplaceOrder order = paidOrder(FulfillmentStatus.READY_TO_FULFILL);
        AtomicReference<MarketplaceOrderCancellation> ledger = stubLedger(order);
        OrderCancellationLedgerWriter.BeginResult begin = writer.begin(
                order.getMarketplaceOrderId(), actor(), "buyer cancellation");

        MarketplaceOrderCancellation result = writer.finish(
                begin.cancellation().getCancellationId(),
                new RefundCoordinator.RefundOutcome(
                        true,
                        "REFUND_SUCCEEDED",
                        "refunded",
                        null,
                        RefundRecord.builder().refundId("RF-1").build()
                ));

        assertEquals(OrderCancellationStatus.COMPLETED, result.getStatus());
        assertEquals(MarketplaceOrderStatus.REFUNDED, order.getStatus());
        assertEquals(FulfillmentStatus.CANCELLED, order.getFulfillmentStatus());
        verify(payoutService).cancelForOrderCancellation(
                "MARKETPLACE_ORDER", order.getMarketplaceOrderId());
        verify(restorationService).restoreCancelledOrder(
                order, "order-cancel-restore-" + order.getMarketplaceOrderId());
        assertEquals(result, ledger.get());
    }

    @Test
    void refundUnknownPreservesOrderPayoutAndInventory() {
        MarketplaceOrder order = paidOrder(FulfillmentStatus.PROCESSING);
        stubLedger(order);
        OrderCancellationLedgerWriter.BeginResult begin = writer.begin(
                order.getMarketplaceOrderId(), actor(), "buyer cancellation");

        MarketplaceOrderCancellation result = writer.finish(
                begin.cancellation().getCancellationId(),
                new RefundCoordinator.RefundOutcome(
                        false, "REFUND_UNKNOWN", "provider result unknown", null, null));

        assertEquals(OrderCancellationStatus.REFUND_UNKNOWN, result.getStatus());
        assertEquals(MarketplaceOrderStatus.PAID, order.getStatus());
        assertEquals(FulfillmentStatus.PROCESSING, order.getFulfillmentStatus());
        verify(payoutService, never()).cancelForOrderCancellation(any(), any());
        verify(restorationService, never()).restoreCancelledOrder(any(), any());
    }

    @Test
    void refundFailurePreservesOrderPayoutAndInventory() {
        MarketplaceOrder order = paidOrder(FulfillmentStatus.READY_TO_FULFILL);
        stubLedger(order);
        OrderCancellationLedgerWriter.BeginResult begin = writer.begin(
                order.getMarketplaceOrderId(), actor(), "buyer cancellation");

        MarketplaceOrderCancellation result = writer.finish(
                begin.cancellation().getCancellationId(),
                new RefundCoordinator.RefundOutcome(
                        false, "REFUND_FAILED", "provider rejected cancellation", null, null));

        assertEquals(OrderCancellationStatus.REFUND_FAILED, result.getStatus());
        assertEquals(MarketplaceOrderStatus.PAID, order.getStatus());
        assertEquals(FulfillmentStatus.READY_TO_FULFILL, order.getFulfillmentStatus());
        verify(payoutService, never()).cancelForOrderCancellation(any(), any());
        verify(restorationService, never()).restoreCancelledOrder(any(), any());
    }

    @Test
    void shippedOrderCannotUseCancellationFlow() {
        MarketplaceOrder order = paidOrder(FulfillmentStatus.SHIPPED);
        when(orderRepository.findByIdForUpdate(order.getMarketplaceOrderId()))
                .thenReturn(Optional.of(order));

        MarketplaceCheckoutException failure = assertThrows(MarketplaceCheckoutException.class,
                () -> writer.begin(order.getMarketplaceOrderId(), actor(), "too late"));

        assertEquals("CANCELLATION_NOT_ALLOWED", failure.getCode());
        verify(cancellationRepository, never()).save(any());
    }

    private AtomicReference<MarketplaceOrderCancellation> stubLedger(MarketplaceOrder order) {
        AtomicReference<MarketplaceOrderCancellation> ledger = new AtomicReference<>();
        when(orderRepository.findByIdForUpdate(order.getMarketplaceOrderId()))
                .thenReturn(Optional.of(order));
        when(cancellationRepository.findByMarketplaceOrderId(order.getMarketplaceOrderId()))
                .thenReturn(Optional.empty());
        when(cancellationRepository.saveAndFlush(any(MarketplaceOrderCancellation.class)))
                .thenAnswer(invocation -> {
                    MarketplaceOrderCancellation value = invocation.getArgument(0);
                    ledger.set(value);
                    return value;
                });
        when(cancellationRepository.findById(any())).thenAnswer(invocation -> Optional.ofNullable(ledger.get()));
        when(orderRepository.save(any(MarketplaceOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
        return ledger;
    }

    private MarketplaceOrder paidOrder(FulfillmentStatus fulfillmentStatus) {
        return MarketplaceOrder.builder()
                .marketplaceOrderId("MORD-1")
                .sellerId("SELLER-1")
                .customerId("CUST-1")
                .productId("PROD-1")
                .quantity(1)
                .paymentId("PAY-1")
                .reservationId("RES-1")
                .status(MarketplaceOrderStatus.PAID)
                .fulfillmentStatus(fulfillmentStatus)
                .build();
    }

    private CommerceActor actor() {
        return new CommerceActor("USER-1", "CUST-1");
    }
}

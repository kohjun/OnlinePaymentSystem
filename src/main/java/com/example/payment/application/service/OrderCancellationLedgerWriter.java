package com.example.payment.application.service;

import com.example.payment.domain.model.marketplace.FulfillmentStatus;
import com.example.payment.domain.model.marketplace.MarketplaceOrder;
import com.example.payment.domain.model.marketplace.MarketplaceOrderCancellation;
import com.example.payment.domain.model.marketplace.MarketplaceOrderStatus;
import com.example.payment.domain.model.marketplace.OrderCancellationStatus;
import com.example.payment.domain.model.marketplace.SellerProfile;
import com.example.payment.domain.repository.MarketplaceOrderCancellationRepository;
import com.example.payment.domain.repository.MarketplaceOrderRepository;
import com.example.payment.domain.repository.SellerProfileRepository;
import com.example.payment.infrastructure.messaging.outbox.OutboxEventService;
import com.example.payment.infrastructure.security.CommerceActor;
import com.example.payment.infrastructure.util.IdGenerator;
import com.example.payment.presentation.dto.response.OrderCancellationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OrderCancellationLedgerWriter {

    private final MarketplaceOrderRepository orderRepository;
    private final MarketplaceOrderCancellationRepository cancellationRepository;
    private final SellerProfileRepository sellerProfileRepository;
    private final SellerPayoutService sellerPayoutService;
    private final InventoryRestorationService inventoryRestorationService;
    private final OutboxEventService outboxEventService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BeginResult begin(String orderId, CommerceActor actor, String reason) {
        MarketplaceOrder order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new MarketplaceCheckoutException(
                        HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "주문을 찾을 수 없습니다."));
        String requesterType = requesterType(order, actor);
        validateCancellable(order);

        MarketplaceOrderCancellation cancellation = cancellationRepository
                .findByMarketplaceOrderId(orderId)
                .orElse(null);
        if (cancellation != null && cancellation.getStatus() == OrderCancellationStatus.COMPLETED) {
            return BeginResult.terminal(cancellation);
        }
        if (cancellation != null && cancellation.getStatus() == OrderCancellationStatus.REFUND_PROCESSING) {
            return BeginResult.terminal(cancellation);
        }
        if (cancellation == null) {
            String cancellationId = "MOCAN-" + IdGenerator.generateEventId();
            cancellation = MarketplaceOrderCancellation.builder()
                    .cancellationId(cancellationId)
                    .marketplaceOrderId(orderId)
                    .paymentId(order.getPaymentId())
                    .refundIdempotencyKey("order-cancel-" + orderId)
                    .requestedByType(requesterType)
                    .requestedById(actor.customerId())
                    .reason(trim(reason))
                    .status(OrderCancellationStatus.REQUESTED)
                    .createdAt(LocalDateTime.now())
                    .build();
        }
        cancellation.setStatus(OrderCancellationStatus.REFUND_PROCESSING);
        cancellation.setFailureReason(null);
        cancellationRepository.saveAndFlush(cancellation);
        recordEvent(order, cancellation, "ORDER_CANCELLATION_UPDATED");
        return BeginResult.ready(cancellation);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MarketplaceOrderCancellation finish(
            String cancellationId, RefundCoordinator.RefundOutcome outcome) {
        MarketplaceOrderCancellation cancellation = cancellationRepository.findById(cancellationId)
                .orElseThrow(() -> new IllegalStateException("Cancellation ledger not found: " + cancellationId));
        MarketplaceOrder order = orderRepository.findByIdForUpdate(cancellation.getMarketplaceOrderId())
                .orElseThrow(() -> new IllegalStateException(
                        "Marketplace order not found: " + cancellation.getMarketplaceOrderId()));
        if (cancellation.getStatus() == OrderCancellationStatus.COMPLETED) {
            return cancellation;
        }
        if (outcome.refund() != null) {
            cancellation.setRefundId(outcome.refund().getRefundId());
        }

        if (outcome.success()) {
            order.setStatus(MarketplaceOrderStatus.REFUNDED);
            order.setFulfillmentStatus(FulfillmentStatus.CANCELLED);
            orderRepository.save(order);
            sellerPayoutService.cancelForOrderCancellation(
                    "MARKETPLACE_ORDER", order.getMarketplaceOrderId());
            try {
                inventoryRestorationService.restoreCancelledOrder(
                        order, "order-cancel-restore-" + order.getMarketplaceOrderId());
            } catch (RuntimeException syncFailure) {
                cancellation.setFailureReason("Inventory restoration queued for reconciliation: "
                        + safeMessage(syncFailure));
            }
            cancellation.setStatus(OrderCancellationStatus.COMPLETED);
            cancellation.setCompletedAt(LocalDateTime.now());
        } else if ("REFUND_UNKNOWN".equals(outcome.code())) {
            cancellation.setStatus(OrderCancellationStatus.REFUND_UNKNOWN);
            cancellation.setFailureReason(outcome.message());
        } else {
            cancellation.setStatus(OrderCancellationStatus.REFUND_FAILED);
            cancellation.setFailureReason(outcome.message());
        }
        cancellationRepository.saveAndFlush(cancellation);
        recordEvent(order, cancellation, "ORDER_CANCELLATION_UPDATED");
        return cancellation;
    }

    public OrderCancellationResponse toResponse(MarketplaceOrderCancellation cancellation) {
        return OrderCancellationResponse.builder()
                .cancellationId(cancellation.getCancellationId())
                .marketplaceOrderId(cancellation.getMarketplaceOrderId())
                .status(cancellation.getStatus())
                .failureReason(cancellation.getFailureReason())
                .createdAt(cancellation.getCreatedAt())
                .completedAt(cancellation.getCompletedAt())
                .build();
    }

    private String requesterType(MarketplaceOrder order, CommerceActor actor) {
        if (order.getCustomerId().equals(actor.customerId())) {
            return "BUYER";
        }
        SellerProfile seller = sellerProfileRepository.findById(order.getSellerId()).orElse(null);
        if (seller != null && ((actor.userId() != null && actor.userId().equals(seller.getOwnerUserId()))
                || actor.customerId().equals(seller.getOwnerCustomerId()))) {
            return "SELLER";
        }
        throw new MarketplaceCheckoutException(
                HttpStatus.FORBIDDEN, "CANCELLATION_NOT_ALLOWED", "주문 취소 권한이 없습니다.");
    }

    private void validateCancellable(MarketplaceOrder order) {
        if (order.getPaymentId() == null || order.getStatus() != MarketplaceOrderStatus.PAID) {
            throw new MarketplaceCheckoutException(
                    HttpStatus.CONFLICT, "CANCELLATION_NOT_ALLOWED", "결제 완료 주문만 취소할 수 있습니다.");
        }
        if (order.getFulfillmentStatus() == FulfillmentStatus.SHIPPED
                || order.getFulfillmentStatus() == FulfillmentStatus.DELIVERED) {
            throw new MarketplaceCheckoutException(
                    HttpStatus.CONFLICT, "CANCELLATION_NOT_ALLOWED", "배송 또는 발급이 완료된 주문은 분쟁 절차를 이용해야 합니다.");
        }
    }

    private void recordEvent(MarketplaceOrder order, MarketplaceOrderCancellation cancellation, String eventType) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("marketplaceOrderId", order.getMarketplaceOrderId());
        payload.put("cancellationId", cancellation.getCancellationId());
        payload.put("customerId", order.getCustomerId());
        payload.put("sellerId", order.getSellerId());
        payload.put("status", cancellation.getStatus());
        if (cancellation.getFailureReason() != null) {
            payload.put("failureReason", cancellation.getFailureReason());
        }
        outboxEventService.record("MARKETPLACE_ORDER", order.getMarketplaceOrderId(), eventType,
                "marketplace-order-events", order.getMarketplaceOrderId(), payload);
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String safeMessage(Throwable failure) {
        return failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName();
    }

    public record BeginResult(boolean ready, MarketplaceOrderCancellation cancellation) {
        static BeginResult ready(MarketplaceOrderCancellation cancellation) {
            return new BeginResult(true, cancellation);
        }

        static BeginResult terminal(MarketplaceOrderCancellation cancellation) {
            return new BeginResult(false, cancellation);
        }
    }
}

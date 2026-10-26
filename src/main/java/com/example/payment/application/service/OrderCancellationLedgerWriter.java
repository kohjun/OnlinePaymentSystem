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
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderCancellationLedgerWriter {

    private final MarketplaceOrderRepository orderRepository;
    private final MarketplaceOrderCancellationRepository cancellationRepository;
    private final SellerProfileRepository sellerProfileRepository;
    private final SellerPayoutService sellerPayoutService;
    private final InventoryRestorationService inventoryRestorationService;
    private final OutboxEventService outboxEventService;
    private final MarketplaceNotificationService notificationService;
    private final com.example.payment.domain.repository.OrderRecordRepository coreOrders;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BeginResult begin(String orderId, CommerceActor actor, String reason) {
        MarketplaceOrder order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new MarketplaceCheckoutException(
                        HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "주문을 찾을 수 없습니다."));
        String requesterType = requesterType(order, actor);

        MarketplaceOrderCancellation cancellation = cancellationRepository
                .findByMarketplaceOrderId(orderId)
                .orElse(null);
        if (cancellation != null && cancellation.getStatus() == OrderCancellationStatus.COMPLETED) {
            return BeginResult.terminal(cancellation);
        }
        if (cancellation != null && cancellation.getStatus() == OrderCancellationStatus.REFUND_PROCESSING) {
            return BeginResult.ready(cancellation);
        }
        validateCancellable(order);
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
        notifyCancellation(order, cancellation);
        return BeginResult.ready(cancellation);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MarketplaceOrderCancellation finish(
            String cancellationId, RefundCoordinator.RefundOutcome outcome) {
        String orderId = cancellationRepository.findOrderId(cancellationId).orElseThrow();
        MarketplaceOrder order = orderRepository.findByIdForUpdate(orderId).orElseThrow();
        // Read after taking the shared order lock, so a late failure cannot overwrite completion.
        MarketplaceOrderCancellation cancellation = cancellationRepository.findById(cancellationId).orElseThrow();
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
            coreOrders.findById(order.getOrderId()).ifPresent(core -> {
                core.setStatus("REFUNDED");
                coreOrders.save(core);
            });
            sellerPayoutService.cancelForOrderCancellation(
                    "MARKETPLACE_ORDER", order.getMarketplaceOrderId());
            inventoryRestorationService.restoreCancelledOrder(
                    order, "order-cancel-restore-" + order.getMarketplaceOrderId());
            cancellation.setStatus(OrderCancellationStatus.COMPLETED);
            cancellation.setFailureReason(null);
            cancellation.setCompletedAt(LocalDateTime.now());
        } else if ("REFUND_UNKNOWN".equals(outcome.code()) || "REFUND_PROCESSING".equals(outcome.code())) {
            cancellation.setStatus(OrderCancellationStatus.REFUND_UNKNOWN);
            cancellation.setFailureReason(outcome.message());
        } else {
            cancellation.setStatus(OrderCancellationStatus.REFUND_FAILED);
            cancellation.setFailureReason(outcome.message());
        }
        cancellationRepository.saveAndFlush(cancellation);
        recordEvent(order, cancellation, "ORDER_CANCELLATION_UPDATED");
        notifyCancellation(order, cancellation);
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
        if (order.getShippedAt() != null || order.getFulfilledAt() != null
                || order.getFulfillmentStatus() == FulfillmentStatus.SHIPPED
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

    private void notifyCancellation(MarketplaceOrder order, MarketplaceOrderCancellation cancellation) {
        String status = cancellation.getStatus().name();
        String message = "주문 " + order.getMarketplaceOrderId() + "의 취소 상태는 " + status + "입니다.";
        notificationService.notifyCustomer(
                order.getCustomerId(),
                "ORDER_CANCELLATION_UPDATED",
                cancellation.getStatus() == OrderCancellationStatus.COMPLETED ? "주문 취소와 환불이 완료되었습니다" : "주문 취소 상태가 변경되었습니다",
                message,
                "MARKETPLACE_ORDER",
                order.getMarketplaceOrderId(),
                "order:" + order.getMarketplaceOrderId() + ":cancellation:" + status + ":buyer"
        );
        notificationService.notifySeller(
                order.getSellerId(),
                "ORDER_CANCELLATION_UPDATED",
                "주문 취소 상태가 변경되었습니다",
                message,
                "MARKETPLACE_ORDER",
                order.getMarketplaceOrderId(),
                "order:" + order.getMarketplaceOrderId() + ":cancellation:" + status + ":seller"
        );
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

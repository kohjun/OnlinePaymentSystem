package com.example.payment.application.service;

import com.example.payment.domain.model.marketplace.FulfillmentStatus;
import com.example.payment.domain.model.marketplace.DisputeResolution;
import com.example.payment.domain.model.marketplace.MarketplaceCheckoutType;
import com.example.payment.domain.model.marketplace.MarketplaceListing;
import com.example.payment.domain.model.marketplace.MarketplaceOrder;
import com.example.payment.domain.model.marketplace.MarketplaceOrderStatus;
import com.example.payment.domain.model.marketplace.SaleEvent;
import com.example.payment.domain.repository.MarketplaceOrderRepository;
import com.example.payment.presentation.dto.request.CompleteReservationRequest;
import com.example.payment.presentation.dto.request.MarketplaceCheckoutRequest;
import com.example.payment.presentation.dto.response.CompleteReservationResponse;
import com.example.payment.presentation.dto.response.MarketplaceOrderResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MarketplaceOrderService {

    private final MarketplaceOrderRepository marketplaceOrderRepository;
    private final SellerPayoutService sellerPayoutService;
    private final PaymentProcessingService paymentProcessingService;
    private final MarketplaceNotificationService notificationService;
    private final CommerceSettlementGuard settlementGuard;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;
    private final com.example.payment.domain.repository.MarketplaceOrderCancellationRepository cancellations;
    private final com.example.payment.domain.repository.OrderRecordRepository coreOrders;
    private final InventoryRestorationService inventoryRestoration;

    @Transactional
    public void recordCheckout(SaleEvent event,
                               MarketplaceListing listing,
                               MarketplaceCheckoutRequest request,
                               CompleteReservationResponse response,
                               MarketplaceCheckoutType checkoutType,
                               String sourceId,
                               BigDecimal authorizedAmount) {
        if (!isTrackableCheckout(response)) {
            return;
        }

        Optional<MarketplaceOrder> existing = findExisting(response, request.getCustomerId());
        MarketplaceOrder order = existing.orElseGet(() -> MarketplaceOrder.builder()
                .marketplaceOrderId("MORD-" + shortId())
                .saleEventId(event.getSaleEventId())
                .listingId(listing.getListingId())
                .sellerId(event.getSellerId())
                .customerId(request.getCustomerId())
                .saleType(event.getSaleType())
                .checkoutType(checkoutType)
                .productId(event.getProductId())
                .quantity(request.getQuantity())
                .sourceId(sourceId)
                .seatId(request.getSeatId())
                .createdAt(LocalDateTime.now())
                .build());

        boolean newlyPaid = order.getStatus() != MarketplaceOrderStatus.PAID;
        order.setSeatId(request.getSeatId());
        applyCheckoutResponse(order, request, response, authorizedAmount, isDigitalTicket(listing));
        applyShippingSnapshot(order, request.getShippingInfo());
        MarketplaceOrder savedOrder = marketplaceOrderRepository.save(order);
        if (savedOrder.getStatus() == MarketplaceOrderStatus.PAID) {
            sellerPayoutService.createHeldPayout(
                    savedOrder.getSellerId(),
                    "MARKETPLACE_ORDER",
                    savedOrder.getMarketplaceOrderId(),
                    savedOrder.getAmount()
            );
            if (newlyPaid) {
                notifyOrderPaid(savedOrder);
            }
        }
    }

    @Transactional(readOnly = true)
    public List<MarketplaceOrderResponse> getCustomerOrders(String customerId) {
        return getCustomerOrders(customerId, 0, 50);
    }

    @Transactional(readOnly = true)
    public List<MarketplaceOrderResponse> getCustomerOrders(String customerId, int page, int size) {
        return marketplaceOrderRepository.findByCustomerIdOrderByCreatedAtDesc(
                        customerId, PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100))))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MarketplaceOrderResponse> getSellerOrders(String sellerId) {
        return getSellerOrders(sellerId, 0, 50);
    }

    @Transactional(readOnly = true)
    public List<MarketplaceOrderResponse> getSellerOrders(String sellerId, int page, int size) {
        return marketplaceOrderRepository.findBySellerIdOrderByCreatedAtDesc(
                        sellerId, PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100))))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public MarketplaceOrderResponse updateFulfillment(String sellerId,
                                                      String marketplaceOrderId,
                                                      FulfillmentStatus fulfillmentStatus) {
        return updateFulfillment(sellerId, marketplaceOrderId, fulfillmentStatus, null, null);
    }

    @Transactional
    public MarketplaceOrderResponse updateFulfillment(String sellerId,
                                                      String marketplaceOrderId,
                                                      FulfillmentStatus fulfillmentStatus,
                                                      String trackingCarrier,
                                                      String trackingNumber) {
        MarketplaceOrder order = marketplaceOrderRepository
                .findByIdForUpdate(marketplaceOrderId)
                .filter(candidate -> sellerId.equals(candidate.getSellerId()))
                .orElseThrow(() -> new IllegalArgumentException("Marketplace order not found: " + marketplaceOrderId));
        settlementGuard.requireClear(order);

        FulfillmentStatus previousStatus = order.getFulfillmentStatus();
        validateFulfillmentTransition(order, fulfillmentStatus, trackingCarrier, trackingNumber);
        order.setFulfillmentStatus(fulfillmentStatus);
        if (fulfillmentStatus == FulfillmentStatus.CANCELLED) {
            order.setStatus(MarketplaceOrderStatus.CANCELLED);
        }
        if (fulfillmentStatus == FulfillmentStatus.SHIPPED) {
            order.setTrackingCarrier(trimToNull(trackingCarrier));
            order.setTrackingNumber(trimToNull(trackingNumber));
            if (order.getShippedAt() == null) {
                order.setShippedAt(LocalDateTime.now());
            }
        }
        if (fulfillmentStatus == FulfillmentStatus.DELIVERED) {
            order.setFulfilledAt(LocalDateTime.now());
        }
        MarketplaceOrder savedOrder = marketplaceOrderRepository.save(order);
        if (previousStatus != fulfillmentStatus) {
            notifyFulfillmentUpdated(savedOrder);
        }
        return toResponse(savedOrder);
    }

    @Transactional
    public MarketplaceOrderResponse confirmDelivery(String customerId, String marketplaceOrderId) {
        MarketplaceOrder order = marketplaceOrderRepository.findByIdForUpdate(marketplaceOrderId)
                .orElseThrow(() -> new IllegalArgumentException("Marketplace order not found: " + marketplaceOrderId));
        requireOrderBuyer(order, customerId);
        settlementGuard.requireClear(order);
        if (order.getStatus() != MarketplaceOrderStatus.PAID) {
            throw new IllegalArgumentException("Only paid marketplace orders can be confirmed.");
        }
        if (order.getDisputedAt() != null) {
            throw new IllegalArgumentException("Disputed marketplace orders cannot be confirmed for payout release.");
        }
        if (order.getFulfillmentStatus() != FulfillmentStatus.SHIPPED
                && order.getFulfillmentStatus() != FulfillmentStatus.DELIVERED) {
            throw new IllegalArgumentException("Marketplace order must be shipped before buyer confirmation.");
        }

        order.setFulfillmentStatus(FulfillmentStatus.DELIVERED);
        if (order.getFulfilledAt() == null) {
            order.setFulfilledAt(LocalDateTime.now());
        }
        boolean newlyConfirmed = order.getBuyerConfirmedAt() == null;
        if (newlyConfirmed) {
            order.setBuyerConfirmedAt(LocalDateTime.now());
            sellerPayoutService.markReadyForRelease("MARKETPLACE_ORDER", order.getMarketplaceOrderId());
        }
        MarketplaceOrder savedOrder = marketplaceOrderRepository.save(order);
        if (newlyConfirmed) {
            notificationService.notifySeller(
                    savedOrder.getSellerId(),
                    "DELIVERY_CONFIRMED",
                    "구매자가 거래를 확정했습니다",
                    "주문 " + savedOrder.getMarketplaceOrderId() + "의 정산 준비가 시작되었습니다.",
                    "MARKETPLACE_ORDER",
                    savedOrder.getMarketplaceOrderId(),
                    "order:" + savedOrder.getMarketplaceOrderId() + ":delivery-confirmed"
            );
        }
        return toResponse(savedOrder);
    }

    @Transactional
    public MarketplaceOrderResponse openDispute(String customerId, String marketplaceOrderId, String reason) {
        MarketplaceOrder order = marketplaceOrderRepository.findByIdForUpdate(marketplaceOrderId)
                .orElseThrow(() -> new IllegalArgumentException("Marketplace order not found: " + marketplaceOrderId));
        requireOrderBuyer(order, customerId);
        if (order.getStatus() != MarketplaceOrderStatus.PAID) {
            throw new IllegalArgumentException("Only paid marketplace orders can be disputed.");
        }
        if (order.getBuyerConfirmedAt() != null) {
            throw new IllegalArgumentException("Buyer-confirmed marketplace orders cannot be disputed through this flow.");
        }
        boolean newlyDisputed = order.getDisputedAt() == null;
        if (newlyDisputed) {
            order.setDisputedAt(LocalDateTime.now());
            order.setDisputeReason(trimToNull(reason));
            sellerPayoutService.markDisputed("MARKETPLACE_ORDER", order.getMarketplaceOrderId());
        }
        MarketplaceOrder savedOrder = marketplaceOrderRepository.save(order);
        if (newlyDisputed) {
            notificationService.notifySeller(
                    savedOrder.getSellerId(),
                    "DISPUTE_OPENED",
                    "구매자가 분쟁을 접수했습니다",
                    "주문 " + savedOrder.getMarketplaceOrderId() + "의 배송과 정산이 보류되었습니다.",
                    "MARKETPLACE_ORDER",
                    savedOrder.getMarketplaceOrderId(),
                    "order:" + savedOrder.getMarketplaceOrderId() + ":dispute-opened:seller"
            );
            notificationService.notifyCustomer(
                    savedOrder.getCustomerId(),
                    "DISPUTE_OPENED",
                    "분쟁 접수가 완료되었습니다",
                    "운영팀이 주문 " + savedOrder.getMarketplaceOrderId() + "을 확인합니다.",
                    "MARKETPLACE_ORDER",
                    savedOrder.getMarketplaceOrderId(),
                    "order:" + savedOrder.getMarketplaceOrderId() + ":dispute-opened:buyer"
            );
        }
        return toResponse(savedOrder);
    }

    public MarketplaceOrderResponse resolveDispute(String operatorId, String marketplaceOrderId,
            DisputeResolution resolution, String note) {
        if (trimToNull(operatorId) == null || resolution == null) {
            throw new IllegalArgumentException("Dispute resolution and operator are required.");
        }
        PaymentProcessingService.RefundResult outcome = null;
        if (resolution == DisputeResolution.BUYER_REFUND) {
            MarketplaceOrder snapshot = marketplaceOrderRepository.findById(marketplaceOrderId).orElseThrow();
            if (snapshot.getDisputedAt() == null) throw new IllegalArgumentException("Marketplace order is not disputed");
            if (snapshot.getDisputeResolvedAt() != null) return toResponse(snapshot);
            outcome = refundDisputedPayment(snapshot, note);
        }
        final PaymentProcessingService.RefundResult observed = outcome;
        return new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(tx -> {
            MarketplaceOrder order = marketplaceOrderRepository.findByIdForUpdate(marketplaceOrderId).orElseThrow();
            if (order.getDisputedAt() == null) throw new IllegalArgumentException("Marketplace order is not disputed");
            if (order.getDisputeResolvedAt() != null) return toResponse(order);
            if (resolution == DisputeResolution.BUYER_REFUND && !observed.success()) {
                order.setStatus("REFUND_UNKNOWN".equals(observed.code()) || "REFUND_PROCESSING".equals(observed.code())
                        ? MarketplaceOrderStatus.REFUND_UNKNOWN : MarketplaceOrderStatus.REFUND_FAILED);
                order.setDisputeResolutionNote(failedRefundNote(note, observed));
                return toResponse(marketplaceOrderRepository.save(order));
            }
            order.setDisputeResolution(resolution);
            order.setDisputeResolutionNote(trimToNull(note));
            order.setDisputeResolvedBy(trimToNull(operatorId));
            order.setDisputeResolvedAt(LocalDateTime.now());
            if (resolution == DisputeResolution.PAYOUT_READY) {
                settlementGuard.requireClear(order);
                order.setFulfillmentStatus(FulfillmentStatus.DELIVERED);
                if (order.getFulfilledAt() == null) order.setFulfilledAt(LocalDateTime.now());
                sellerPayoutService.markReadyAfterDisputeResolution(order);
            } else {
                order.setStatus(resolution == DisputeResolution.BUYER_REFUND
                        ? MarketplaceOrderStatus.REFUNDED : MarketplaceOrderStatus.CANCELLED);
                order.setFulfillmentStatus(FulfillmentStatus.CANCELLED);
                sellerPayoutService.markCancelled("MARKETPLACE_ORDER", order.getMarketplaceOrderId());
                if (resolution == DisputeResolution.BUYER_REFUND) {
                    coreOrders.findById(order.getOrderId()).ifPresent(core -> { core.setStatus("REFUNDED"); coreOrders.save(core); });
                    if (order.getShippedAt() == null && order.getFulfilledAt() == null) {
                        inventoryRestoration.restoreCancelledOrder(order, "order-cancel-restore-" + order.getMarketplaceOrderId());
                    }
                }
            }
            MarketplaceOrder saved = marketplaceOrderRepository.save(order);
            notifyDisputeResolved(saved, resolution);
            return toResponse(saved);
        });
    }

    @Transactional
    public Optional<MarketplaceOrderResponse> syncProviderRefundStatus(String paymentId, String paymentStatus) {
        if (trimToNull(paymentId) == null || trimToNull(paymentStatus) == null) {
            return Optional.empty();
        }
        return marketplaceOrderRepository.findByPaymentId(paymentId)
                .map(snapshot -> {
                    MarketplaceOrder order = marketplaceOrderRepository.findByIdForUpdate(snapshot.getMarketplaceOrderId()).orElseThrow();
                    if (order.getStatus() == MarketplaceOrderStatus.REFUNDED) return toResponse(order);
                    if ("REFUNDED".equals(paymentStatus) && order.getShippedAt() == null && order.getFulfilledAt() == null
                            && order.getFulfillmentStatus() != FulfillmentStatus.SHIPPED
                            && order.getFulfillmentStatus() != FulfillmentStatus.DELIVERED) {
                        inventoryRestoration.restoreCancelledOrder(order, "order-cancel-restore-" + order.getMarketplaceOrderId());
                    }
                    if ("PARTIALLY_REFUNDED".equals(paymentStatus)) {
                        applyPartialProviderRefund(order);
                    } else if ("REFUNDED".equals(paymentStatus)) {
                        applyFullProviderRefund(order);
                    } else {
                        return toResponse(order);
                    }
                    coreOrders.findById(order.getOrderId()).ifPresent(core -> {
                        core.setStatus(paymentStatus);
                        coreOrders.save(core);
                    });
                    MarketplaceOrder savedOrder = marketplaceOrderRepository.save(order);
                    notificationService.notifyCustomer(
                            savedOrder.getCustomerId(),
                            "REFUND_UPDATED",
                            "환불 상태가 변경되었습니다",
                            "주문 " + savedOrder.getMarketplaceOrderId() + "의 결제 상태는 " + savedOrder.getStatus() + "입니다.",
                            "MARKETPLACE_ORDER",
                            savedOrder.getMarketplaceOrderId(),
                            "order:" + savedOrder.getMarketplaceOrderId() + ":provider-refund:" + savedOrder.getStatus()
                    );
                    return toResponse(savedOrder);
                });
    }

    private void notifyOrderPaid(MarketplaceOrder order) {
        notificationService.notifyCustomer(
                order.getCustomerId(),
                "PAYMENT_COMPLETED",
                "결제가 완료되었습니다",
                "주문 " + order.getMarketplaceOrderId() + "이 정상적으로 접수되었습니다.",
                "MARKETPLACE_ORDER",
                order.getMarketplaceOrderId(),
                "order:" + order.getMarketplaceOrderId() + ":paid:buyer"
        );
        notificationService.notifySeller(
                order.getSellerId(),
                "NEW_ORDER",
                "새 주문이 접수되었습니다",
                "주문 " + order.getMarketplaceOrderId() + "의 배송 준비를 시작해 주세요.",
                "MARKETPLACE_ORDER",
                order.getMarketplaceOrderId(),
                "order:" + order.getMarketplaceOrderId() + ":paid:seller"
        );
    }

    private void notifyFulfillmentUpdated(MarketplaceOrder order) {
        String title;
        String message;
        if (order.getFulfillmentStatus() == FulfillmentStatus.SHIPPED) {
            title = "상품이 발송되었습니다";
            message = "주문 " + order.getMarketplaceOrderId() + "의 배송 조회가 가능합니다.";
        } else if (order.getFulfillmentStatus() == FulfillmentStatus.DELIVERED) {
            title = "배송이 완료되었습니다";
            message = "상품을 확인한 뒤 구매를 확정하거나 문제가 있으면 분쟁을 접수해 주세요.";
        } else {
            title = "주문 처리 상태가 변경되었습니다";
            message = "주문 " + order.getMarketplaceOrderId() + "의 상태는 " + order.getFulfillmentStatus() + "입니다.";
        }
        notificationService.notifyCustomer(
                order.getCustomerId(),
                "FULFILLMENT_UPDATED",
                title,
                message,
                "MARKETPLACE_ORDER",
                order.getMarketplaceOrderId(),
                "order:" + order.getMarketplaceOrderId() + ":fulfillment:" + order.getFulfillmentStatus()
        );
    }

    private void notifyDisputeResolved(MarketplaceOrder order, DisputeResolution resolution) {
        String message = "주문 " + order.getMarketplaceOrderId() + "의 분쟁 처리 결과는 " + resolution + "입니다.";
        notificationService.notifyCustomer(
                order.getCustomerId(),
                "DISPUTE_RESOLVED",
                "분쟁 처리가 완료되었습니다",
                message,
                "MARKETPLACE_ORDER",
                order.getMarketplaceOrderId(),
                "order:" + order.getMarketplaceOrderId() + ":dispute-resolved:" + resolution + ":buyer"
        );
        notificationService.notifySeller(
                order.getSellerId(),
                "DISPUTE_RESOLVED",
                "분쟁 처리가 완료되었습니다",
                message,
                "MARKETPLACE_ORDER",
                order.getMarketplaceOrderId(),
                "order:" + order.getMarketplaceOrderId() + ":dispute-resolved:" + resolution + ":seller"
        );
    }

    private void applyFullProviderRefund(MarketplaceOrder order) {
        order.setStatus(MarketplaceOrderStatus.REFUNDED);
        order.setFulfillmentStatus(FulfillmentStatus.CANCELLED);
        if (order.getDisputedAt() == null) {
            order.setDisputedAt(LocalDateTime.now());
            order.setDisputeReason("Provider full refund received.");
        }
        if (order.getDisputeResolution() == null) {
            order.setDisputeResolution(DisputeResolution.BUYER_REFUND);
            order.setDisputeResolutionNote("Synchronized from Toss full cancel webhook.");
            order.setDisputeResolvedBy("TOSS_WEBHOOK");
            order.setDisputeResolvedAt(LocalDateTime.now());
        }
        sellerPayoutService.applyProviderRefundStatus("MARKETPLACE_ORDER", order.getMarketplaceOrderId(), false);
    }

    private void applyPartialProviderRefund(MarketplaceOrder order) {
        if (order.getStatus() != MarketplaceOrderStatus.REFUNDED) {
            order.setStatus(MarketplaceOrderStatus.PARTIALLY_REFUNDED);
        }
        if (order.getDisputedAt() == null) {
            order.setDisputedAt(LocalDateTime.now());
            order.setDisputeReason("Provider partial refund received; operations review required.");
        }
        sellerPayoutService.applyProviderRefundStatus("MARKETPLACE_ORDER", order.getMarketplaceOrderId(), true);
    }

    private PaymentProcessingService.RefundResult refundDisputedPayment(MarketplaceOrder order, String note) {
        if (trimToNull(order.getPaymentId()) == null) {
            throw new IllegalArgumentException("Marketplace order has no payment to refund: " + order.getMarketplaceOrderId());
        }
        String idempotencyKey = "dispute-" + order.getMarketplaceOrderId();
        String reason = trimToNull(note) != null
                ? "C2C dispute refund: " + trimToNull(note)
                : "C2C dispute refund: " + order.getMarketplaceOrderId();
        return paymentProcessingService.refundPaymentWithResult(order.getPaymentId(), idempotencyKey, reason);
    }

    private String failedRefundNote(String note, PaymentProcessingService.RefundResult refundResult) {
        String base = trimToNull(note);
        String failure = "Refund failed: " + refundResult.code() + " - " + refundResult.message();
        if (base == null) {
            return failure;
        }
        return base + " | " + failure;
    }

    private boolean isTrackableCheckout(CompleteReservationResponse response) {
        return response != null && ("SUCCESS".equals(response.getStatus()) || "PENDING".equals(response.getStatus()));
    }

    private Optional<MarketplaceOrder> findExisting(CompleteReservationResponse response, String customerId) {
        String orderId = orderId(response);
        if (orderId != null) {
            Optional<MarketplaceOrder> byOrderId = marketplaceOrderRepository.findByOrderId(orderId);
            if (byOrderId.isPresent()) {
                return byOrderId;
            }
        }
        if (response.getWorkflowId() != null) {
            return marketplaceOrderRepository.findByWorkflowIdAndCustomerId(response.getWorkflowId(), customerId);
        }
        return Optional.empty();
    }

    private void applyCheckoutResponse(MarketplaceOrder order,
                                       MarketplaceCheckoutRequest request,
                                       CompleteReservationResponse response,
                                       BigDecimal authorizedAmount,
                                       boolean digitalTicket) {
        boolean success = "SUCCESS".equals(response.getStatus());
        order.setStatus(success ? MarketplaceOrderStatus.PAID : MarketplaceOrderStatus.PENDING);
        order.setFulfillmentStatus(success
                ? digitalTicket ? FulfillmentStatus.DELIVERED : FulfillmentStatus.READY_TO_FULFILL
                : FulfillmentStatus.NOT_READY);
        order.setReservationId(reservationId(response));
        order.setOrderId(orderId(response));
        order.setPaymentId(paymentId(response));
        order.setWorkflowId(response.getWorkflowId());
        order.setAmount(paymentAmount(response, authorizedAmount));
        order.setCurrency(currency(response, request));
        order.setPaidAt(success ? paidAt(response) : null);
        if (success && digitalTicket && order.getFulfilledAt() == null) {
            order.setFulfilledAt(LocalDateTime.now());
        }
    }

    private void validateFulfillmentTransition(MarketplaceOrder order,
                                               FulfillmentStatus nextStatus,
                                               String trackingCarrier,
                                               String trackingNumber) {
        if (nextStatus == FulfillmentStatus.NOT_READY) {
            throw new IllegalArgumentException("Fulfillment cannot be moved back to NOT_READY.");
        }
        if (nextStatus == FulfillmentStatus.CANCELLED) {
            throw new IllegalArgumentException("CANCELLATION_API_REQUIRED");
        }
        if (order.getDisputedAt() != null && nextStatus != FulfillmentStatus.CANCELLED) {
            throw new IllegalArgumentException("Disputed marketplace orders cannot be fulfilled until the dispute is resolved.");
        }
        if (nextStatus != FulfillmentStatus.CANCELLED && order.getStatus() != MarketplaceOrderStatus.PAID) {
            throw new IllegalArgumentException("Only paid marketplace orders can be fulfilled.");
        }
        if (nextStatus == FulfillmentStatus.SHIPPED
                && (trimToNull(trackingCarrier) == null || trimToNull(trackingNumber) == null)) {
            throw new IllegalArgumentException("Tracking carrier and tracking number are required when shipping a marketplace order.");
        }
        if (order.getFulfillmentStatus() == FulfillmentStatus.DELIVERED && nextStatus != FulfillmentStatus.DELIVERED) {
            throw new IllegalArgumentException("Delivered marketplace orders cannot be changed.");
        }
        if ((order.getFulfillmentStatus() == FulfillmentStatus.SHIPPED || order.getShippedAt() != null)
                && nextStatus != FulfillmentStatus.SHIPPED && nextStatus != FulfillmentStatus.DELIVERED) {
            throw new IllegalArgumentException("Shipped marketplace orders cannot return to pre-shipment status.");
        }
    }

    private void requireOrderBuyer(MarketplaceOrder order, String customerId) {
        if (!order.getCustomerId().equals(customerId)) {
            throw new IllegalArgumentException("Marketplace order does not belong to customer: " + customerId);
        }
    }

    private String reservationId(CompleteReservationResponse response) {
        return response.getReservation() != null ? response.getReservation().getReservationId() : null;
    }

    private String orderId(CompleteReservationResponse response) {
        return response.getOrder() != null ? response.getOrder().getOrderId() : null;
    }

    private String paymentId(CompleteReservationResponse response) {
        return response.getPayment() != null ? response.getPayment().getPaymentId() : null;
    }

    private BigDecimal paymentAmount(CompleteReservationResponse response, BigDecimal authorizedAmount) {
        if (response.getPayment() != null && response.getPayment().getAmount() != null) {
            return response.getPayment().getAmount();
        }
        return authorizedAmount;
    }

    private String currency(CompleteReservationResponse response, MarketplaceCheckoutRequest request) {
        if (response.getPayment() != null && response.getPayment().getCurrency() != null) {
            return response.getPayment().getCurrency();
        }
        if (request.getPaymentInfo() != null && request.getPaymentInfo().getCurrency() != null) {
            return request.getPaymentInfo().getCurrency();
        }
        return "KRW";
    }

    private LocalDateTime paidAt(CompleteReservationResponse response) {
        if (response.getPayment() != null && response.getPayment().getProcessedAt() != null) {
            return response.getPayment().getProcessedAt();
        }
        return LocalDateTime.now();
    }

    private MarketplaceOrderResponse toResponse(MarketplaceOrder order) {
        var cancellation = cancellations.findByMarketplaceOrderId(order.getMarketplaceOrderId()).orElse(null);
        return MarketplaceOrderResponse.builder()
                .cancellationStatus(cancellation == null ? null : cancellation.getStatus().name())
                .marketplaceOrderId(order.getMarketplaceOrderId())
                .saleEventId(order.getSaleEventId())
                .listingId(order.getListingId())
                .sellerId(order.getSellerId())
                .customerId(order.getCustomerId())
                .saleType(order.getSaleType())
                .checkoutType(order.getCheckoutType())
                .status(order.getStatus())
                .fulfillmentStatus(order.getFulfillmentStatus())
                .productId(order.getProductId())
                .quantity(order.getQuantity())
                .amount(order.getAmount())
                .currency(order.getCurrency())
                .reservationId(order.getReservationId())
                .orderId(order.getOrderId())
                .paymentId(order.getPaymentId())
                .workflowId(order.getWorkflowId())
                .sourceId(order.getSourceId())
                .seatId(order.getSeatId())
                .shippingAddressId(order.getShippingAddressId())
                .shippingRecipientName(order.getShippingRecipientName())
                .shippingContactPhone(order.getShippingContactPhone())
                .shippingPostalCode(order.getShippingPostalCode())
                .shippingAddress(order.getShippingAddress())
                .shippingMethod(order.getShippingMethod())
                .shippingMemo(order.getShippingMemo())
                .createdAt(order.getCreatedAt())
                .paidAt(order.getPaidAt())
                .updatedAt(order.getUpdatedAt())
                .fulfilledAt(order.getFulfilledAt())
                .shippedAt(order.getShippedAt())
                .trackingCarrier(order.getTrackingCarrier())
                .trackingNumber(order.getTrackingNumber())
                .buyerConfirmedAt(order.getBuyerConfirmedAt())
                .disputedAt(order.getDisputedAt())
                .disputeReason(order.getDisputeReason())
                .disputeResolution(order.getDisputeResolution())
                .disputeResolutionNote(order.getDisputeResolutionNote())
                .disputeResolvedBy(order.getDisputeResolvedBy())
                .disputeResolvedAt(order.getDisputeResolvedAt())
                .build();
    }

    private boolean isDigitalTicket(MarketplaceListing listing) {
        return listing != null && "DIGITAL_TICKET".equalsIgnoreCase(listing.getItemCondition());
    }

    private void applyShippingSnapshot(MarketplaceOrder order, CompleteReservationRequest.ShippingInfo shippingInfo) {
        if (shippingInfo == null) {
            return;
        }
        order.setShippingAddressId(shippingInfo.getAddressId());
        order.setShippingRecipientName(shippingInfo.getRecipientName());
        order.setShippingContactPhone(shippingInfo.getContactPhone());
        order.setShippingPostalCode(shippingInfo.getPostalCode());
        order.setShippingAddress(shippingInfo.getAddress());
        order.setShippingMethod(shippingInfo.getMethod());
        order.setShippingMemo(shippingInfo.getSpecialInstructions());
    }

    private String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }
}

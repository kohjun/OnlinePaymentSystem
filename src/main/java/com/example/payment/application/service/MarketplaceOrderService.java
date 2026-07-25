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
                .findByMarketplaceOrderIdAndSellerId(marketplaceOrderId, sellerId)
                .orElseThrow(() -> new IllegalArgumentException("Marketplace order not found: " + marketplaceOrderId));

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
        return toResponse(marketplaceOrderRepository.save(order));
    }

    @Transactional
    public MarketplaceOrderResponse confirmDelivery(String customerId, String marketplaceOrderId) {
        MarketplaceOrder order = marketplaceOrderRepository.findById(marketplaceOrderId)
                .orElseThrow(() -> new IllegalArgumentException("Marketplace order not found: " + marketplaceOrderId));
        requireOrderBuyer(order, customerId);
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
        if (order.getBuyerConfirmedAt() == null) {
            order.setBuyerConfirmedAt(LocalDateTime.now());
            sellerPayoutService.markReadyForRelease("MARKETPLACE_ORDER", order.getMarketplaceOrderId());
        }
        return toResponse(marketplaceOrderRepository.save(order));
    }

    @Transactional
    public MarketplaceOrderResponse openDispute(String customerId, String marketplaceOrderId, String reason) {
        MarketplaceOrder order = marketplaceOrderRepository.findById(marketplaceOrderId)
                .orElseThrow(() -> new IllegalArgumentException("Marketplace order not found: " + marketplaceOrderId));
        requireOrderBuyer(order, customerId);
        if (order.getStatus() != MarketplaceOrderStatus.PAID) {
            throw new IllegalArgumentException("Only paid marketplace orders can be disputed.");
        }
        if (order.getBuyerConfirmedAt() != null) {
            throw new IllegalArgumentException("Buyer-confirmed marketplace orders cannot be disputed through this flow.");
        }
        if (order.getDisputedAt() == null) {
            order.setDisputedAt(LocalDateTime.now());
            order.setDisputeReason(trimToNull(reason));
            sellerPayoutService.markDisputed("MARKETPLACE_ORDER", order.getMarketplaceOrderId());
        }
        return toResponse(marketplaceOrderRepository.save(order));
    }

    @Transactional
    public MarketplaceOrderResponse resolveDispute(String operatorId,
                                                   String marketplaceOrderId,
                                                   DisputeResolution resolution,
                                                   String note) {
        MarketplaceOrder order = marketplaceOrderRepository.findById(marketplaceOrderId)
                .orElseThrow(() -> new IllegalArgumentException("Marketplace order not found: " + marketplaceOrderId));
        if (order.getDisputedAt() == null) {
            throw new IllegalArgumentException("Marketplace order is not disputed: " + marketplaceOrderId);
        }
        if (order.getDisputeResolvedAt() != null) {
            return toResponse(order);
        }
        PaymentProcessingService.RefundResult refundResult = null;
        if (resolution == DisputeResolution.BUYER_REFUND) {
            refundResult = refundDisputedPayment(order, note);
            if (!refundResult.success()) {
                order.setStatus(MarketplaceOrderStatus.REFUND_FAILED);
                order.setDisputeResolutionNote(failedRefundNote(note, refundResult));
                return toResponse(marketplaceOrderRepository.save(order));
            }
        }

        order.setDisputeResolution(resolution);
        order.setDisputeResolutionNote(trimToNull(note));
        order.setDisputeResolvedBy(trimToNull(operatorId));
        order.setDisputeResolvedAt(LocalDateTime.now());

        if (resolution == DisputeResolution.PAYOUT_READY) {
            order.setFulfillmentStatus(FulfillmentStatus.DELIVERED);
            if (order.getFulfilledAt() == null) {
                order.setFulfilledAt(LocalDateTime.now());
            }
            sellerPayoutService.markReadyForRelease("MARKETPLACE_ORDER", order.getMarketplaceOrderId());
        } else if (resolution == DisputeResolution.PAYOUT_CANCELLED) {
            order.setStatus(MarketplaceOrderStatus.CANCELLED);
            order.setFulfillmentStatus(FulfillmentStatus.CANCELLED);
            sellerPayoutService.markCancelled("MARKETPLACE_ORDER", order.getMarketplaceOrderId());
        } else if (resolution == DisputeResolution.BUYER_REFUND) {
            order.setStatus(MarketplaceOrderStatus.REFUNDED);
            order.setFulfillmentStatus(FulfillmentStatus.CANCELLED);
            sellerPayoutService.markCancelled("MARKETPLACE_ORDER", order.getMarketplaceOrderId());
        }
        return toResponse(marketplaceOrderRepository.save(order));
    }

    @Transactional
    public Optional<MarketplaceOrderResponse> syncProviderRefundStatus(String paymentId, String paymentStatus) {
        if (trimToNull(paymentId) == null || trimToNull(paymentStatus) == null) {
            return Optional.empty();
        }
        return marketplaceOrderRepository.findByPaymentId(paymentId)
                .map(order -> {
                    if ("PARTIALLY_REFUNDED".equals(paymentStatus)) {
                        applyPartialProviderRefund(order);
                    } else if ("REFUNDED".equals(paymentStatus)) {
                        applyFullProviderRefund(order);
                    } else {
                        return toResponse(order);
                    }
                    return toResponse(marketplaceOrderRepository.save(order));
                });
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
        return MarketplaceOrderResponse.builder()
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

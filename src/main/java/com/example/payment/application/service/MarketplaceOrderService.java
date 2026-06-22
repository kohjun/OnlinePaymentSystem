package com.example.payment.application.service;

import com.example.payment.domain.model.marketplace.FulfillmentStatus;
import com.example.payment.domain.model.marketplace.MarketplaceCheckoutType;
import com.example.payment.domain.model.marketplace.MarketplaceListing;
import com.example.payment.domain.model.marketplace.MarketplaceOrder;
import com.example.payment.domain.model.marketplace.MarketplaceOrderStatus;
import com.example.payment.domain.model.marketplace.SaleEvent;
import com.example.payment.domain.repository.MarketplaceOrderRepository;
import com.example.payment.presentation.dto.request.MarketplaceCheckoutRequest;
import com.example.payment.presentation.dto.response.CompleteReservationResponse;
import com.example.payment.presentation.dto.response.MarketplaceOrderResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
                .createdAt(LocalDateTime.now())
                .build());

        applyCheckoutResponse(order, request, response, authorizedAmount);
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
        return marketplaceOrderRepository.findByCustomerIdOrderByCreatedAtDesc(customerId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MarketplaceOrderResponse> getSellerOrders(String sellerId) {
        return marketplaceOrderRepository.findBySellerIdOrderByCreatedAtDesc(sellerId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public MarketplaceOrderResponse updateFulfillment(String sellerId,
                                                      String marketplaceOrderId,
                                                      FulfillmentStatus fulfillmentStatus) {
        MarketplaceOrder order = marketplaceOrderRepository
                .findByMarketplaceOrderIdAndSellerId(marketplaceOrderId, sellerId)
                .orElseThrow(() -> new IllegalArgumentException("Marketplace order not found: " + marketplaceOrderId));

        validateFulfillmentTransition(order, fulfillmentStatus);
        order.setFulfillmentStatus(fulfillmentStatus);
        if (fulfillmentStatus == FulfillmentStatus.CANCELLED) {
            order.setStatus(MarketplaceOrderStatus.CANCELLED);
        }
        if (fulfillmentStatus == FulfillmentStatus.DELIVERED) {
            order.setFulfilledAt(LocalDateTime.now());
        }
        return toResponse(marketplaceOrderRepository.save(order));
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
                                       BigDecimal authorizedAmount) {
        boolean success = "SUCCESS".equals(response.getStatus());
        order.setStatus(success ? MarketplaceOrderStatus.PAID : MarketplaceOrderStatus.PENDING);
        order.setFulfillmentStatus(success ? FulfillmentStatus.READY_TO_FULFILL : FulfillmentStatus.NOT_READY);
        order.setReservationId(reservationId(response));
        order.setOrderId(orderId(response));
        order.setPaymentId(paymentId(response));
        order.setWorkflowId(response.getWorkflowId());
        order.setAmount(paymentAmount(response, authorizedAmount));
        order.setCurrency(currency(response, request));
        order.setPaidAt(success ? paidAt(response) : null);
    }

    private void validateFulfillmentTransition(MarketplaceOrder order, FulfillmentStatus nextStatus) {
        if (nextStatus == FulfillmentStatus.NOT_READY) {
            throw new IllegalArgumentException("Fulfillment cannot be moved back to NOT_READY.");
        }
        if (nextStatus != FulfillmentStatus.CANCELLED && order.getStatus() != MarketplaceOrderStatus.PAID) {
            throw new IllegalArgumentException("Only paid marketplace orders can be fulfilled.");
        }
        if (order.getFulfillmentStatus() == FulfillmentStatus.DELIVERED && nextStatus != FulfillmentStatus.DELIVERED) {
            throw new IllegalArgumentException("Delivered marketplace orders cannot be changed.");
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
                .createdAt(order.getCreatedAt())
                .paidAt(order.getPaidAt())
                .updatedAt(order.getUpdatedAt())
                .fulfilledAt(order.getFulfilledAt())
                .build();
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }
}

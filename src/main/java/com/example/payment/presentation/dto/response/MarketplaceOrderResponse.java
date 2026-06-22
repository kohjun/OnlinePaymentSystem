package com.example.payment.presentation.dto.response;

import com.example.payment.domain.model.marketplace.FulfillmentStatus;
import com.example.payment.domain.model.marketplace.MarketplaceCheckoutType;
import com.example.payment.domain.model.marketplace.MarketplaceOrderStatus;
import com.example.payment.domain.model.marketplace.SaleType;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class MarketplaceOrderResponse {

    private String marketplaceOrderId;
    private String saleEventId;
    private String listingId;
    private String sellerId;
    private String customerId;
    private SaleType saleType;
    private MarketplaceCheckoutType checkoutType;
    private MarketplaceOrderStatus status;
    private FulfillmentStatus fulfillmentStatus;
    private String productId;
    private Integer quantity;
    private BigDecimal amount;
    private String currency;
    private String reservationId;
    private String orderId;
    private String paymentId;
    private String workflowId;
    private String sourceId;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime paidAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime fulfilledAt;
}

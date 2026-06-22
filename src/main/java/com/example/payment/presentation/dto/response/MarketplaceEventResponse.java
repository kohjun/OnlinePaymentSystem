package com.example.payment.presentation.dto.response;

import com.example.payment.domain.model.marketplace.SaleEventStatus;
import com.example.payment.domain.model.marketplace.SaleType;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class MarketplaceEventResponse {
    private String saleEventId;
    private String listingId;
    private String sellerId;
    private String sellerName;
    private String productId;
    private String title;
    private String description;
    private String imageUrl;
    private String category;
    private SaleType saleType;
    private SaleEventStatus status;
    private BigDecimal price;
    private String currency;
    private Integer totalQuantity;
    private Integer availableQuantity;
    private BigDecimal minBidIncrement;
    private BigDecimal reservePrice;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime startsAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime endsAt;
}

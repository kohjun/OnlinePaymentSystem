package com.example.payment.presentation.dto.response;

import com.example.payment.domain.model.marketplace.ListingStatus;
import com.example.payment.domain.model.marketplace.SaleEventStatus;
import com.example.payment.domain.model.marketplace.SaleType;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class SellerListingResponse {
    private String listingId;
    private String sellerId;
    private String productId;
    private String name;
    private String description;
    private String imageUrl;
    private String category;
    private ListingStatus status;
    private BigDecimal price;
    private Integer totalQuantity;
    private Integer availableQuantity;
    private String saleEventId;
    private SaleType saleType;
    private SaleEventStatus saleEventStatus;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime startsAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime endsAt;

    private String reviewedBy;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime reviewedAt;

    private String reviewNote;
}

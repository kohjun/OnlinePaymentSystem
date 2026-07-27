package com.example.payment.presentation.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class WishlistItemResponse {
    private String wishlistItemId;
    private String saleEventId;
    private LocalDateTime createdAt;

    /** 판매가 끝났거나 비공개로 바뀐 이벤트는 비어 있을 수 있다. */
    private MarketplaceEventResponse event;
}

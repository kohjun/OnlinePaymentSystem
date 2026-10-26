package com.example.payment.presentation.dto.response;

import com.example.payment.domain.model.marketplace.ListingMediaType;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@lombok.extern.jackson.Jacksonized
public class ListingMediaResponse {
    private String mediaId;
    private String listingId;
    private ListingMediaType mediaType;
    private String fileName;
    private String contentType;
    private long sizeBytes;
    private int sortOrder;
    private String url;
}

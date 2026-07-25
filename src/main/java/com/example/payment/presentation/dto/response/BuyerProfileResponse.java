package com.example.payment.presentation.dto.response;

import com.example.payment.domain.model.account.BuyerStatus;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BuyerProfileResponse {
    private String userId;
    private String customerId;
    private String displayName;
    private BuyerStatus status;
}

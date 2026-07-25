package com.example.payment.presentation.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.Set;

@Data
@Builder
public class MeResponse {
    private String userId;
    private String customerId;
    private String sellerId;
    private Set<String> roles;
    private UserAccountResponse user;
    private BuyerProfileResponse buyerProfile;
    private SellerResponse sellerProfile;
}

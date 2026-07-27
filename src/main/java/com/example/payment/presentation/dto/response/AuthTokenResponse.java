package com.example.payment.presentation.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AuthTokenResponse {
    private String accessToken;
    private String tokenType;
    private long expiresIn;
    private String userId;
    private String customerId;
    private String email;
    private String displayName;
    private List<String> roles;
}

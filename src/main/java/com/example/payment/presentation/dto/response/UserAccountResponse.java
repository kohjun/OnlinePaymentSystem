package com.example.payment.presentation.dto.response;

import com.example.payment.domain.model.account.UserAccountStatus;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserAccountResponse {
    private String userId;
    private String customerId;
    private String email;
    private String displayName;
    private UserAccountStatus status;
}

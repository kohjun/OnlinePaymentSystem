package com.example.payment.application.temporal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundCompensationResult {
    private boolean successful;
    private String status;
    private String message;
}

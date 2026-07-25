package com.example.payment.application.service;

import com.example.payment.domain.model.marketplace.PayoutTransferStatus;

import java.math.BigDecimal;

public interface SellerPayoutTransferGateway {

    String providerName();

    TransferResult transfer(TransferRequest request);

    TransferResult getStatus(String providerTransferId, String idempotencyKey);

    record TransferRequest(
            String payoutId,
            String sellerId,
            String accountRef,
            BigDecimal amount,
            String currency,
            String idempotencyKey
    ) {
    }

    record TransferResult(
            PayoutTransferStatus status,
            String providerTransferId,
            String failureReason
    ) {
        public static TransferResult succeeded(String providerTransferId) {
            return new TransferResult(PayoutTransferStatus.SUCCEEDED, providerTransferId, null);
        }
    }
}

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

    // Provider observations are not final ledger decisions; the writer verifies receipt evidence.
    record TransferResult(
            PayoutTransferStatus status,
            String providerTransferId,
            String failureReason,
            String referenceKey,
            BigDecimal amount,
            String currency
    ) {
        public TransferResult(PayoutTransferStatus status, String providerTransferId, String failureReason) {
            this(status, providerTransferId, failureReason, null, null, null);
        }

        public static TransferResult unknown(String reason) {
            return new TransferResult(PayoutTransferStatus.UNKNOWN, null, reason);
        }

        public static TransferResult succeeded(String providerTransferId) {
            return new TransferResult(PayoutTransferStatus.SUCCEEDED, providerTransferId, null);
        }
    }
}

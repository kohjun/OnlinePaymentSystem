package com.example.payment.application.service;

import com.example.payment.domain.model.marketplace.PayoutTransferStatus;

import java.math.BigDecimal;

public interface SellerPayoutTransferGateway {

    String providerName();

    /**
     * 제공자가 공개·계약한 API 스펙(경로, 본문 암호화, 수취인 등록, 상태 조회)과
     * sandbox 증빙으로 맞춰 본 어댑터인지. 기본값은 false라서, 새 어댑터는
     * 검증을 마치고 명시적으로 선언해야 상용 readiness를 통과한다.
     */
    default boolean providerContractVerified() {
        return false;
    }

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

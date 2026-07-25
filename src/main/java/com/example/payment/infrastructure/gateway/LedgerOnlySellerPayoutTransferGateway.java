package com.example.payment.infrastructure.gateway;

import com.example.payment.application.service.SellerPayoutTransferGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "app.payout.transfer.provider",
        havingValue = "LEDGER_ONLY",
        matchIfMissing = true
)
public class LedgerOnlySellerPayoutTransferGateway implements SellerPayoutTransferGateway {

    @Override
    public String providerName() {
        return "LEDGER_ONLY";
    }

    @Override
    public TransferResult transfer(TransferRequest request) {
        return TransferResult.succeeded("LEDGER-" + request.payoutId());
    }

    @Override
    public TransferResult getStatus(String providerTransferId, String idempotencyKey) {
        return TransferResult.succeeded(providerTransferId);
    }
}

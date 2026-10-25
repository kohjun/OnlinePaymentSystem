package com.example.payment.application.service;

import com.example.payment.domain.model.marketplace.*;
import com.example.payment.domain.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PayoutTransferClaimWriter {
    private final SellerPayoutRepository payouts;
    private final SellerPayoutTransferRepository transfers;
    private final MarketplaceOrderRepository orders;
    private final CommerceSettlementGuard guard;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Claim claim(String payoutId, String provider) {
        var order = payouts.findMarketplaceOrderId(payoutId)
                .map(id -> orders.findByIdForUpdate(id).orElseThrow()).orElse(null);
        var payout = payouts.findByPayoutIdForUpdate(payoutId).orElseThrow();
        String key = "seller-payout:" + payoutId;
        var existing = transfers.findByPayoutIdAndIdempotencyKey(payoutId, key).orElse(null);
        if (existing != null) return new Claim(existing, false);
        if (order != null) guard.requireClear(order);
        if (payout.getStatus() != SellerPayoutStatus.READY_FOR_RELEASE) {
            throw new IllegalStateException("Payout is not ready for release");
        }
        var transfer = transfers.saveAndFlush(SellerPayoutTransfer.builder()
                .transferId("PTR-" + UUID.randomUUID()).payoutId(payoutId).idempotencyKey(key)
                .amount(payout.getNetAmount()).currency("KRW").provider(provider)
                .status(PayoutTransferStatus.PROCESSING).attemptCount(1)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        return new Claim(transfer, true);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SellerPayoutTransfer observe(String transferId, SellerPayoutTransferGateway.TransferResult result) {
        String payoutId = transfers.findPayoutId(transferId).orElseThrow();
        var order = payouts.findMarketplaceOrderId(payoutId)
                .map(id -> orders.findByIdForUpdate(id).orElseThrow()).orElse(null);
        var payout = payouts.findByPayoutIdForUpdate(payoutId).orElseThrow();
        var transfer = transfers.findById(transferId).orElseThrow();
        if (transfer.getStatus() == PayoutTransferStatus.SUCCEEDED) return transfer;
        if (!verifiable(transfer, result)) {
            result = SellerPayoutTransferGateway.TransferResult.unknown("PAYOUT_RESPONSE_MISMATCH");
        }
        transfer.setStatus(result.status());
        if (result.providerTransferId() != null) transfer.setProviderTransferId(result.providerTransferId());
        transfer.setFailureReason(result.failureReason());
        if (result.status() == PayoutTransferStatus.SUCCEEDED) {
            transfer.setCompletedAt(LocalDateTime.now());
            boolean clear = payout.getStatus() == SellerPayoutStatus.READY_FOR_RELEASE;
            if (order != null) {
                try { guard.requireClear(order); }
                catch (IllegalStateException blocked) { clear = false; }
            }
            payout.setStatus(clear ? SellerPayoutStatus.RELEASED : SellerPayoutStatus.RECOVERY_REQUIRED);
            payout.setReleasedAt(LocalDateTime.now());
            payouts.save(payout);
        }
        return transfers.saveAndFlush(transfer);
    }

    public record Claim(SellerPayoutTransfer transfer, boolean submit) {}

    private boolean verifiable(SellerPayoutTransfer transfer, SellerPayoutTransferGateway.TransferResult result) {
        if (result == null || result.status() == null) return false;
        if (result.providerTransferId() != null && transfer.getProviderTransferId() != null
                && !transfer.getProviderTransferId().equals(result.providerTransferId())) return false;
        // Transport errors may carry no receipt. They must not establish a new provider identity.
        if (result.status() == PayoutTransferStatus.UNKNOWN && result.referenceKey() == null) {
            return result.providerTransferId() == null
                    || result.providerTransferId().equals(transfer.getProviderTransferId());
        }
        return result.providerTransferId() != null && !result.providerTransferId().isBlank()
                && transfer.getIdempotencyKey() != null && transfer.getIdempotencyKey().equals(result.referenceKey())
                && transfer.getAmount() != null && result.amount() != null
                && transfer.getAmount().compareTo(result.amount()) == 0
                && transfer.getCurrency() != null && transfer.getCurrency().equals(result.currency());
    }
}

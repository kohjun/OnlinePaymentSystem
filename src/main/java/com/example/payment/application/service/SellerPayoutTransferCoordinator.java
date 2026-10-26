package com.example.payment.application.service;

import com.example.payment.domain.model.marketplace.PayoutTransferStatus;
import com.example.payment.domain.model.marketplace.SellerPayout;
import com.example.payment.domain.model.marketplace.SellerPayoutAccount;
import com.example.payment.domain.model.marketplace.SellerPayoutAccountStatus;
import com.example.payment.domain.model.marketplace.SellerPayoutStatus;
import com.example.payment.domain.model.marketplace.SellerPayoutTransfer;
import com.example.payment.domain.repository.SellerPayoutAccountRepository;
import com.example.payment.domain.repository.SellerPayoutRepository;
import com.example.payment.domain.repository.SellerPayoutTransferRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SellerPayoutTransferCoordinator {

    private final SellerPayoutTransferRepository transferRepository;
    private final SellerPayoutAccountRepository payoutAccountRepository;
    private final SellerPayoutRepository payoutRepository;
    private final ObjectProvider<SellerPayoutTransferGateway> gatewayProvider;
    private final PayoutTransferClaimWriter writer;

    public SellerPayoutTransfer transfer(SellerPayout payout) {
        SellerPayoutTransferGateway gateway = gatewayProvider.getIfAvailable();
        if (gateway == null) {
            throw new IllegalStateException("Seller payout transfer gateway is not configured.");
        }

        SellerPayoutAccount account = payoutAccountRepository.findBySellerId(payout.getSellerId())
                .filter(candidate -> candidate.getStatus() == SellerPayoutAccountStatus.VERIFIED)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Verified seller payout account is required before payout release: " + payout.getSellerId()));
        String gatewayName = gateway.providerName();
        if (gatewayName == null || gatewayName.isBlank()) {
            throw new IllegalStateException("Seller payout transfer gateway provider name is missing.");
        }
        String idempotencyKey = "seller-payout:" + payout.getPayoutId();
        var claim = writer.claim(payout.getPayoutId(), gatewayName);
        SellerPayoutTransfer transfer = claim.transfer();

        if (transfer.getStatus() == PayoutTransferStatus.SUCCEEDED) {
            return transfer;
        }
        if (transfer.getProvider() == null || !gatewayName.equalsIgnoreCase(transfer.getProvider())) {
            throw new IllegalStateException("Configured payout provider does not match the existing transfer provider.");
        }

        SellerPayoutTransferGateway.TransferResult result;
        if (!claim.submit()) {
            result = gateway.getStatus(transfer.getProviderTransferId(), idempotencyKey);
        } else {
            try {
                result = gateway.transfer(new SellerPayoutTransferGateway.TransferRequest(
                        payout.getPayoutId(),
                        payout.getSellerId(),
                        account.getAccountRef(),
                        transfer.getAmount(),
                        transfer.getCurrency(),
                        idempotencyKey
                ));
            } catch (RuntimeException e) {
                writer.observe(transfer.getTransferId(), SellerPayoutTransferGateway.TransferResult.unknown("Payout response unavailable"));
                throw new IllegalStateException("Payout transfer result is unknown: " + payout.getPayoutId(), e);
            }
        }

        SellerPayoutTransfer saved = applyResult(transfer, result);
        if (saved.getStatus() != PayoutTransferStatus.SUCCEEDED) {
            throw new IllegalStateException("Payout transfer did not succeed: " + saved.getStatus());
        }
        return saved;
    }

    public int reconcileStaleTransfers(Duration staleAfter, int batchSize) {
        if (staleAfter == null || staleAfter.isNegative() || staleAfter.isZero()) {
            throw new IllegalArgumentException("staleAfter must be positive.");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive.");
        }

        SellerPayoutTransferGateway gateway = gatewayProvider.getIfAvailable();
        if (gateway == null) {
            throw new IllegalStateException("Seller payout transfer gateway is not configured.");
        }
        LocalDateTime updatedBefore = LocalDateTime.now().minus(staleAfter);
        List<SellerPayoutTransfer> staleTransfers = transferRepository
                .findByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                        Set.of(PayoutTransferStatus.PROCESSING, PayoutTransferStatus.UNKNOWN),
                        updatedBefore,
                        PageRequest.of(0, batchSize));

        int reconciled = 0;
        for (SellerPayoutTransfer transfer : staleTransfers) {
            String gatewayProviderName = gateway.providerName();
            if (gatewayProviderName == null || transfer.getProvider() == null
                    || !gatewayProviderName.equalsIgnoreCase(transfer.getProvider())) {
                writer.observe(transfer.getTransferId(), SellerPayoutTransferGateway.TransferResult.unknown("Payout provider mismatch"));
                continue;
            }

            try {
                SellerPayoutTransferGateway.TransferResult result = gateway.getStatus(
                        transfer.getProviderTransferId(),
                        transfer.getIdempotencyKey());
                SellerPayoutTransfer saved = applyResult(transfer, result);
                reconciled++;
            } catch (RuntimeException exception) {
                writer.observe(transfer.getTransferId(), SellerPayoutTransferGateway.TransferResult.unknown("Payout lookup unavailable"));
            }
        }
        return reconciled;
    }

    private SellerPayoutTransfer applyResult(SellerPayoutTransfer transfer,
                                            SellerPayoutTransferGateway.TransferResult result) {
        return writer.observe(transfer.getTransferId(), result);
    }

    private String safeFailureReason(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }
}

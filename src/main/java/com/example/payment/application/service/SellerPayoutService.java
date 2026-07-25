package com.example.payment.application.service;

import com.example.payment.domain.model.marketplace.SellerPayout;
import com.example.payment.domain.model.marketplace.SellerPayoutAccountStatus;
import com.example.payment.domain.model.marketplace.SellerPayoutStatus;
import com.example.payment.domain.repository.SellerPayoutAccountRepository;
import com.example.payment.domain.repository.SellerPayoutRepository;
import com.example.payment.presentation.dto.response.SellerPayoutResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class SellerPayoutService {

    private static final BigDecimal PLATFORM_FEE_RATE = new BigDecimal("0.10");

    private final SellerPayoutRepository sellerPayoutRepository;
    private final SellerPayoutAccountRepository sellerPayoutAccountRepository;
    private final SellerPayoutTransferCoordinator payoutTransferCoordinator;

    public SellerPayoutService(SellerPayoutRepository sellerPayoutRepository,
                               SellerPayoutAccountRepository sellerPayoutAccountRepository,
                               SellerPayoutTransferCoordinator payoutTransferCoordinator) {
        this.sellerPayoutRepository = sellerPayoutRepository;
        this.sellerPayoutAccountRepository = sellerPayoutAccountRepository;
        this.payoutTransferCoordinator = payoutTransferCoordinator;
    }

    @Transactional
    public void createHeldPayout(String sellerId, String sourceType, String sourceId, BigDecimal grossAmount) {
        if (sellerPayoutRepository.existsBySourceTypeAndSourceId(sourceType, sourceId)) {
            return;
        }

        BigDecimal platformFee = grossAmount.multiply(PLATFORM_FEE_RATE).setScale(2, RoundingMode.HALF_UP);
        sellerPayoutRepository.save(SellerPayout.builder()
                .payoutId("PAYOUT-" + shortId())
                .sellerId(sellerId)
                .sourceType(sourceType)
                .sourceId(sourceId)
                .grossAmount(grossAmount)
                .platformFee(platformFee)
                .netAmount(grossAmount.subtract(platformFee))
                .status(SellerPayoutStatus.HELD)
                .createdAt(LocalDateTime.now())
                .build());
    }

    @Transactional(readOnly = true)
    public List<SellerPayoutResponse> getSellerPayouts(String sellerId, SellerPayoutStatus status) {
        List<SellerPayout> payouts = status == null
                ? sellerPayoutRepository.findBySellerIdOrderByCreatedAtDesc(sellerId)
                : sellerPayoutRepository.findBySellerIdAndStatusOrderByCreatedAtDesc(sellerId, status);
        return payouts.stream().map(this::toResponse).toList();
    }

    public SellerPayoutResponse releasePayout(String sellerId, String payoutId) {
        SellerPayout payout = sellerPayoutRepository.findByPayoutIdAndSellerId(payoutId, sellerId)
                .orElseThrow(() -> new IllegalArgumentException("Seller payout not found: " + payoutId));
        return release(payout);
    }

    public SellerPayoutResponse releasePayout(String payoutId) {
        SellerPayout payout = sellerPayoutRepository.findById(payoutId)
                .orElseThrow(() -> new IllegalArgumentException("Seller payout not found: " + payoutId));
        return release(payout);
    }

    private SellerPayoutResponse release(SellerPayout payout) {
        if (payout.getStatus() == SellerPayoutStatus.RELEASED) {
            return toResponse(payout);
        }
        if (payout.getStatus() != SellerPayoutStatus.READY_FOR_RELEASE) {
            throw new IllegalArgumentException("Only ready payouts can be released: " + payout.getPayoutId());
        }
        if (!sellerPayoutAccountRepository.existsBySellerIdAndStatus(payout.getSellerId(), SellerPayoutAccountStatus.VERIFIED)) {
            throw new IllegalArgumentException("Verified seller payout account is required before payout release: " + payout.getSellerId());
        }
        payoutTransferCoordinator.transfer(payout);
        payout.setStatus(SellerPayoutStatus.RELEASED);
        payout.setReleasedAt(LocalDateTime.now());
        return toResponse(sellerPayoutRepository.save(payout));
    }

    @Transactional
    public SellerPayoutResponse markReadyForRelease(String sourceType, String sourceId) {
        SellerPayout payout = sellerPayoutRepository.findBySourceTypeAndSourceId(sourceType, sourceId)
                .orElseThrow(() -> new IllegalArgumentException("Seller payout source not found: " + sourceType + "/" + sourceId));
        if (payout.getStatus() == SellerPayoutStatus.RELEASED) {
            return toResponse(payout);
        }
        if (payout.getStatus() == SellerPayoutStatus.DISPUTED || payout.getStatus() == SellerPayoutStatus.CANCELLED) {
            throw new IllegalArgumentException("Payout cannot be marked ready from status " + payout.getStatus() + ": " + payout.getPayoutId());
        }
        payout.setStatus(SellerPayoutStatus.READY_FOR_RELEASE);
        return toResponse(sellerPayoutRepository.save(payout));
    }

    @Transactional
    public SellerPayoutResponse markDisputed(String sourceType, String sourceId) {
        SellerPayout payout = sellerPayoutRepository.findBySourceTypeAndSourceId(sourceType, sourceId)
                .orElseThrow(() -> new IllegalArgumentException("Seller payout source not found: " + sourceType + "/" + sourceId));
        if (payout.getStatus() == SellerPayoutStatus.RELEASED) {
            throw new IllegalArgumentException("Released payout cannot be disputed: " + payout.getPayoutId());
        }
        payout.setStatus(SellerPayoutStatus.DISPUTED);
        return toResponse(sellerPayoutRepository.save(payout));
    }

    @Transactional
    public SellerPayoutResponse markCancelled(String sourceType, String sourceId) {
        SellerPayout payout = sellerPayoutRepository.findBySourceTypeAndSourceId(sourceType, sourceId)
                .orElseThrow(() -> new IllegalArgumentException("Seller payout source not found: " + sourceType + "/" + sourceId));
        if (payout.getStatus() == SellerPayoutStatus.RELEASED) {
            throw new IllegalArgumentException("Released payout cannot be cancelled: " + payout.getPayoutId());
        }
        payout.setStatus(SellerPayoutStatus.CANCELLED);
        return toResponse(sellerPayoutRepository.save(payout));
    }

    @Transactional
    public Optional<SellerPayoutResponse> applyProviderRefundStatus(String sourceType, String sourceId, boolean partialRefund) {
        return sellerPayoutRepository.findBySourceTypeAndSourceId(sourceType, sourceId)
                .map(payout -> {
                    if (payout.getStatus() == SellerPayoutStatus.RELEASED) {
                        payout.setStatus(SellerPayoutStatus.RECOVERY_REQUIRED);
                    } else if (partialRefund) {
                        payout.setStatus(SellerPayoutStatus.DISPUTED);
                    } else {
                        payout.setStatus(SellerPayoutStatus.CANCELLED);
                    }
                    return toResponse(sellerPayoutRepository.save(payout));
                });
    }

    @Transactional
    public SellerPayoutResponse markRecovered(String payoutId) {
        SellerPayout payout = sellerPayoutRepository.findById(payoutId)
                .orElseThrow(() -> new IllegalArgumentException("Seller payout not found: " + payoutId));
        if (payout.getStatus() == SellerPayoutStatus.RECOVERED) {
            return toResponse(payout);
        }
        if (payout.getStatus() != SellerPayoutStatus.RECOVERY_REQUIRED) {
            throw new IllegalArgumentException("Only recovery-required payouts can be marked recovered: " + payoutId);
        }
        payout.setStatus(SellerPayoutStatus.RECOVERED);
        return toResponse(sellerPayoutRepository.save(payout));
    }

    private SellerPayoutResponse toResponse(SellerPayout payout) {
        return SellerPayoutResponse.builder()
                .payoutId(payout.getPayoutId())
                .sellerId(payout.getSellerId())
                .sourceType(payout.getSourceType())
                .sourceId(payout.getSourceId())
                .grossAmount(payout.getGrossAmount())
                .platformFee(payout.getPlatformFee())
                .netAmount(payout.getNetAmount())
                .status(payout.getStatus())
                .createdAt(payout.getCreatedAt())
                .releasedAt(payout.getReleasedAt())
                .build();
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }
}

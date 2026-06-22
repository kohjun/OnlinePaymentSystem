package com.example.payment.application.service;

import com.example.payment.domain.model.marketplace.SellerPayout;
import com.example.payment.domain.model.marketplace.SellerPayoutStatus;
import com.example.payment.domain.repository.SellerPayoutRepository;
import com.example.payment.presentation.dto.response.SellerPayoutResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SellerPayoutService {

    private static final BigDecimal PLATFORM_FEE_RATE = new BigDecimal("0.10");

    private final SellerPayoutRepository sellerPayoutRepository;

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

    @Transactional
    public SellerPayoutResponse releasePayout(String sellerId, String payoutId) {
        SellerPayout payout = sellerPayoutRepository.findByPayoutIdAndSellerId(payoutId, sellerId)
                .orElseThrow(() -> new IllegalArgumentException("Seller payout not found: " + payoutId));
        if (payout.getStatus() != SellerPayoutStatus.HELD) {
            throw new IllegalArgumentException("Only held payouts can be released: " + payoutId);
        }
        payout.setStatus(SellerPayoutStatus.RELEASED);
        payout.setReleasedAt(LocalDateTime.now());
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

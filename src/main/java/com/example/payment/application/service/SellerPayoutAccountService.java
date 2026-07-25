package com.example.payment.application.service;

import com.example.payment.domain.model.marketplace.SellerPayoutAccount;
import com.example.payment.domain.model.marketplace.SellerPayoutAccountStatus;
import com.example.payment.domain.model.marketplace.SellerProfile;
import com.example.payment.domain.repository.SellerPayoutAccountRepository;
import com.example.payment.domain.repository.SellerProfileRepository;
import com.example.payment.presentation.dto.request.ReviewSellerPayoutAccountRequest;
import com.example.payment.presentation.dto.request.SubmitSellerPayoutAccountRequest;
import com.example.payment.presentation.dto.response.SellerPayoutAccountResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SellerPayoutAccountService {

    private final SellerPayoutAccountRepository payoutAccountRepository;
    private final SellerProfileRepository sellerProfileRepository;

    @Transactional(readOnly = true)
    public Optional<SellerPayoutAccountResponse> getForOwner(String ownerUserId, String ownerCustomerId) {
        SellerProfile seller = requireSellerForOwner(ownerUserId, ownerCustomerId);
        return payoutAccountRepository.findBySellerId(seller.getSellerId()).map(this::toResponse);
    }

    @Transactional
    public SellerPayoutAccountResponse submitForOwner(String ownerUserId,
                                                      String ownerCustomerId,
                                                      SubmitSellerPayoutAccountRequest request) {
        SellerProfile seller = requireSellerForOwner(ownerUserId, ownerCustomerId);
        SellerPayoutAccount account = payoutAccountRepository.findBySellerId(seller.getSellerId())
                .orElseGet(() -> SellerPayoutAccount.builder()
                        .payoutAccountId("PACCT-" + shortId())
                        .sellerId(seller.getSellerId())
                        .createdAt(LocalDateTime.now())
                        .build());

        account.setAccountRef(request.getAccountRef().trim());
        account.setBankCode(request.getBankCode().trim());
        account.setBankName(request.getBankName().trim());
        account.setAccountHolderName(request.getAccountHolderName().trim());
        account.setAccountLast4(request.getAccountLast4().trim());
        account.setStatus(SellerPayoutAccountStatus.PENDING_REVIEW);
        account.setReviewNote(trimToNull(request.getNote()));
        account.setSubmittedAt(LocalDateTime.now());
        account.setReviewedBy(null);
        account.setReviewedAt(null);
        return toResponse(payoutAccountRepository.save(account));
    }

    @Transactional
    public SellerPayoutAccountResponse review(String sellerId,
                                              String operatorId,
                                              ReviewSellerPayoutAccountRequest request) {
        SellerPayoutAccount account = payoutAccountRepository.findBySellerId(sellerId)
                .orElseThrow(() -> new IllegalArgumentException("Seller payout account not found: " + sellerId));
        if (account.getStatus() != SellerPayoutAccountStatus.PENDING_REVIEW) {
            throw new IllegalArgumentException("Seller payout account is not waiting for review: " + sellerId);
        }
        account.setReviewedBy(defaultText(operatorId, "admin"));
        account.setReviewedAt(LocalDateTime.now());
        account.setReviewNote(defaultText(request.getNote(), Boolean.TRUE.equals(request.getApproved())
                ? "Seller payout account verified."
                : "Seller payout account rejected."));
        account.setStatus(Boolean.TRUE.equals(request.getApproved())
                ? SellerPayoutAccountStatus.VERIFIED
                : SellerPayoutAccountStatus.REJECTED);
        return toResponse(payoutAccountRepository.save(account));
    }

    private SellerProfile requireSellerForOwner(String ownerUserId, String ownerCustomerId) {
        if (ownerUserId != null && !ownerUserId.isBlank()) {
            Optional<SellerProfile> byUser = sellerProfileRepository.findByOwnerUserId(ownerUserId);
            if (byUser.isPresent()) {
                return byUser.get();
            }
        }
        if (ownerCustomerId != null && !ownerCustomerId.isBlank()) {
            return sellerProfileRepository.findByOwnerCustomerId(ownerCustomerId)
                    .orElseThrow(() -> new IllegalArgumentException("Seller profile has not been created for the current user."));
        }
        throw new IllegalArgumentException("Seller profile has not been created for the current user.");
    }

    private SellerPayoutAccountResponse toResponse(SellerPayoutAccount account) {
        return SellerPayoutAccountResponse.builder()
                .payoutAccountId(account.getPayoutAccountId())
                .sellerId(account.getSellerId())
                .accountRef(account.getAccountRef())
                .bankCode(account.getBankCode())
                .bankName(account.getBankName())
                .accountHolderName(account.getAccountHolderName())
                .accountLast4(account.getAccountLast4())
                .status(account.getStatus())
                .reviewNote(account.getReviewNote())
                .submittedAt(account.getSubmittedAt())
                .reviewedBy(account.getReviewedBy())
                .reviewedAt(account.getReviewedAt())
                .build();
    }

    private String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }
}

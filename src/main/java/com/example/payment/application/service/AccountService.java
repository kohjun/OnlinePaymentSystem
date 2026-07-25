package com.example.payment.application.service;

import com.example.payment.domain.model.account.BuyerProfile;
import com.example.payment.domain.model.account.BuyerStatus;
import com.example.payment.domain.model.account.UserAccount;
import com.example.payment.domain.model.account.UserAccountStatus;
import com.example.payment.domain.repository.BuyerProfileRepository;
import com.example.payment.domain.repository.UserAccountRepository;
import com.example.payment.infrastructure.security.EverySalePrincipal;
import com.example.payment.presentation.dto.request.CreateSellerRequest;
import com.example.payment.presentation.dto.response.BuyerProfileResponse;
import com.example.payment.presentation.dto.response.MeResponse;
import com.example.payment.presentation.dto.response.SellerResponse;
import com.example.payment.presentation.dto.response.UserAccountResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final UserAccountRepository userAccountRepository;
    private final BuyerProfileRepository buyerProfileRepository;
    private final SellerMarketplaceService sellerMarketplaceService;

    @Transactional
    public MeResponse getMe(EverySalePrincipal principal) {
        UserAccount user = ensureUser(principal);
        BuyerProfile buyerProfile = ensureBuyerProfile(user);
        Optional<SellerResponse> sellerProfile = sellerMarketplaceService.getSellerByOwner(user.getUserId(), user.getCustomerId());
        return toMeResponse(principal, user, buyerProfile, sellerProfile.orElse(null));
    }

    @Transactional
    public SellerResponse createMySellerProfile(EverySalePrincipal principal, CreateSellerRequest request) {
        UserAccount user = ensureUser(principal);
        ensureBuyerProfile(user);
        return sellerMarketplaceService.createSellerForOwner(
                user.getUserId(),
                user.getCustomerId(),
                request
        );
    }

    @Transactional
    public Optional<SellerResponse> getMySellerProfile(EverySalePrincipal principal) {
        UserAccount user = ensureUser(principal);
        ensureBuyerProfile(user);
        return sellerMarketplaceService.getSellerByOwner(user.getUserId(), user.getCustomerId());
    }

    private UserAccount ensureUser(EverySalePrincipal principal) {
        String customerId = hasText(principal.customerId()) ? principal.customerId() : principal.userId();
        String userId = hasText(principal.userId()) ? principal.userId() : "USER-" + customerId;
        UserAccount user = userAccountRepository.findById(userId)
                .or(() -> userAccountRepository.findByCustomerId(customerId))
                .orElseGet(() -> UserAccount.builder()
                        .userId(userId)
                        .customerId(customerId)
                        .displayName(customerId)
                        .status(UserAccountStatus.ACTIVE)
                        .createdAt(LocalDateTime.now())
                        .build());
        if (!customerId.equals(user.getCustomerId())) {
            user.setCustomerId(customerId);
        }
        if (!hasText(user.getDisplayName())) {
            user.setDisplayName(customerId);
        }
        user.setLastSeenAt(LocalDateTime.now());
        return userAccountRepository.save(user);
    }

    private BuyerProfile ensureBuyerProfile(UserAccount user) {
        BuyerProfile buyerProfile = buyerProfileRepository.findById(user.getUserId())
                .or(() -> buyerProfileRepository.findByCustomerId(user.getCustomerId()))
                .orElseGet(() -> BuyerProfile.builder()
                        .userId(user.getUserId())
                        .customerId(user.getCustomerId())
                        .displayName(user.getDisplayName())
                        .status(BuyerStatus.ACTIVE)
                        .createdAt(LocalDateTime.now())
                        .build());
        if (!user.getCustomerId().equals(buyerProfile.getCustomerId())) {
            buyerProfile.setCustomerId(user.getCustomerId());
        }
        if (!hasText(buyerProfile.getDisplayName())) {
            buyerProfile.setDisplayName(user.getDisplayName());
        }
        return buyerProfileRepository.save(buyerProfile);
    }

    private MeResponse toMeResponse(EverySalePrincipal principal,
                                    UserAccount user,
                                    BuyerProfile buyerProfile,
                                    SellerResponse sellerProfile) {
        return MeResponse.builder()
                .userId(user.getUserId())
                .customerId(user.getCustomerId())
                .sellerId(sellerProfile != null ? sellerProfile.getSellerId() : principal.sellerId())
                .roles(principal.roles())
                .user(toUserResponse(user))
                .buyerProfile(toBuyerResponse(buyerProfile))
                .sellerProfile(sellerProfile)
                .build();
    }

    private UserAccountResponse toUserResponse(UserAccount user) {
        return UserAccountResponse.builder()
                .userId(user.getUserId())
                .customerId(user.getCustomerId())
                .email(user.getEmail())
                .displayName(user.getDisplayName())
                .status(user.getStatus())
                .build();
    }

    private BuyerProfileResponse toBuyerResponse(BuyerProfile buyerProfile) {
        return BuyerProfileResponse.builder()
                .userId(buyerProfile.getUserId())
                .customerId(buyerProfile.getCustomerId())
                .displayName(buyerProfile.getDisplayName())
                .status(buyerProfile.getStatus())
                .build();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}

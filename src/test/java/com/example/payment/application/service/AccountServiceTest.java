package com.example.payment.application.service;

import com.example.payment.domain.model.account.BuyerProfile;
import com.example.payment.domain.model.account.BuyerStatus;
import com.example.payment.domain.model.account.UserAccount;
import com.example.payment.domain.model.account.UserAccountStatus;
import com.example.payment.domain.repository.BuyerProfileRepository;
import com.example.payment.domain.repository.UserAccountRepository;
import com.example.payment.infrastructure.security.EverySalePrincipal;
import com.example.payment.presentation.dto.request.CreateSellerRequest;
import com.example.payment.presentation.dto.response.MeResponse;
import com.example.payment.presentation.dto.response.SellerResponse;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountServiceTest {

    private final UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
    private final BuyerProfileRepository buyerProfileRepository = mock(BuyerProfileRepository.class);
    private final SellerMarketplaceService sellerMarketplaceService = mock(SellerMarketplaceService.class);
    private final AccountService accountService = new AccountService(
            userAccountRepository,
            buyerProfileRepository,
            sellerMarketplaceService
    );

    @Test
    void getMeCreatesUserAndBuyerProfileForPrincipal() {
        EverySalePrincipal principal = new EverySalePrincipal("USER-1", "CUST-1", null, Set.of("CUSTOMER"));
        when(userAccountRepository.findById("USER-1")).thenReturn(Optional.empty());
        when(userAccountRepository.findByCustomerId("CUST-1")).thenReturn(Optional.empty());
        when(userAccountRepository.save(any(UserAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(buyerProfileRepository.findById("USER-1")).thenReturn(Optional.empty());
        when(buyerProfileRepository.findByCustomerId("CUST-1")).thenReturn(Optional.empty());
        when(buyerProfileRepository.save(any(BuyerProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(sellerMarketplaceService.getSellerByOwner("USER-1", "CUST-1")).thenReturn(Optional.empty());

        MeResponse response = accountService.getMe(principal);

        assertEquals("USER-1", response.getUserId());
        assertEquals("CUST-1", response.getCustomerId());
        assertEquals(UserAccountStatus.ACTIVE, response.getUser().getStatus());
        assertEquals(BuyerStatus.ACTIVE, response.getBuyerProfile().getStatus());
        assertNotNull(response.getRoles());
    }

    @Test
    void createMySellerProfileUsesAuthenticatedOwnerIdentity() {
        EverySalePrincipal principal = new EverySalePrincipal("USER-1", "CUST-1", null, Set.of("CUSTOMER"));
        CreateSellerRequest request = new CreateSellerRequest();
        request.setDisplayName("Vintage Seller");
        SellerResponse sellerResponse = SellerResponse.builder()
                .sellerId("SELLER-1")
                .displayName("Vintage Seller")
                .ownerUserId("USER-1")
                .ownerCustomerId("CUST-1")
                .build();

        when(userAccountRepository.findById("USER-1")).thenReturn(Optional.empty());
        when(userAccountRepository.findByCustomerId("CUST-1")).thenReturn(Optional.empty());
        when(userAccountRepository.save(any(UserAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(buyerProfileRepository.findById("USER-1")).thenReturn(Optional.empty());
        when(buyerProfileRepository.findByCustomerId("CUST-1")).thenReturn(Optional.empty());
        when(buyerProfileRepository.save(any(BuyerProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(sellerMarketplaceService.createSellerForOwner("USER-1", "CUST-1", request)).thenReturn(sellerResponse);

        SellerResponse response = accountService.createMySellerProfile(principal, request);

        assertEquals("SELLER-1", response.getSellerId());
        verify(sellerMarketplaceService).createSellerForOwner("USER-1", "CUST-1", request);
    }
}

package com.example.payment.application.service;

import com.example.payment.domain.model.marketplace.SellerPayoutAccount;
import com.example.payment.domain.model.marketplace.SellerPayoutAccountStatus;
import com.example.payment.domain.model.marketplace.SellerProfile;
import com.example.payment.domain.model.marketplace.SellerStatus;
import com.example.payment.domain.model.marketplace.SellerVerificationStatus;
import com.example.payment.domain.repository.SellerPayoutAccountRepository;
import com.example.payment.domain.repository.SellerProfileRepository;
import com.example.payment.presentation.dto.request.ReviewSellerPayoutAccountRequest;
import com.example.payment.presentation.dto.request.SubmitSellerPayoutAccountRequest;
import com.example.payment.presentation.dto.response.SellerPayoutAccountResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SellerPayoutAccountServiceTest {

    private final SellerPayoutAccountRepository payoutAccountRepository = mock(SellerPayoutAccountRepository.class);
    private final SellerProfileRepository sellerProfileRepository = mock(SellerProfileRepository.class);
    private final SellerPayoutAccountService service = new SellerPayoutAccountService(
            payoutAccountRepository,
            sellerProfileRepository
    );

    @Test
    void submitsPayoutAccountForSellerOwnerReview() {
        when(sellerProfileRepository.findByOwnerUserId("USER-1")).thenReturn(Optional.of(seller()));
        when(payoutAccountRepository.findBySellerId("SELLER-1")).thenReturn(Optional.empty());
        when(payoutAccountRepository.save(any(SellerPayoutAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SubmitSellerPayoutAccountRequest request = new SubmitSellerPayoutAccountRequest();
        request.setAccountRef("vault://accounts/seller-1");
        request.setBankCode("088");
        request.setBankName("Shinhan Bank");
        request.setAccountHolderName("Every Seller");
        request.setAccountLast4("1234");
        request.setNote("Settlement account evidence submitted.");

        SellerPayoutAccountResponse response = service.submitForOwner("USER-1", "CUST-1", request);

        assertEquals("SELLER-1", response.getSellerId());
        assertEquals("vault://accounts/seller-1", response.getAccountRef());
        assertEquals("1234", response.getAccountLast4());
        assertEquals(SellerPayoutAccountStatus.PENDING_REVIEW, response.getStatus());
    }

    @Test
    void approvesPendingPayoutAccount() {
        SellerPayoutAccount account = account(SellerPayoutAccountStatus.PENDING_REVIEW);
        when(payoutAccountRepository.findBySellerId("SELLER-1")).thenReturn(Optional.of(account));
        when(payoutAccountRepository.save(any(SellerPayoutAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReviewSellerPayoutAccountRequest request = new ReviewSellerPayoutAccountRequest();
        request.setApproved(true);
        request.setNote("Bank account holder matched seller profile.");

        SellerPayoutAccountResponse response = service.review("SELLER-1", "ops-1", request);

        assertEquals(SellerPayoutAccountStatus.VERIFIED, response.getStatus());
        assertEquals("ops-1", response.getReviewedBy());
        assertEquals("Bank account holder matched seller profile.", response.getReviewNote());
    }

    @Test
    void rejectsReviewWhenPayoutAccountIsNotPending() {
        when(payoutAccountRepository.findBySellerId("SELLER-1")).thenReturn(Optional.of(account(SellerPayoutAccountStatus.VERIFIED)));

        ReviewSellerPayoutAccountRequest request = new ReviewSellerPayoutAccountRequest();
        request.setApproved(false);

        assertThrows(IllegalArgumentException.class, () -> service.review("SELLER-1", "ops-1", request));
    }

    private SellerProfile seller() {
        return SellerProfile.builder()
                .sellerId("SELLER-1")
                .displayName("Every Seller")
                .ownerUserId("USER-1")
                .ownerCustomerId("CUST-1")
                .status(SellerStatus.ACTIVE)
                .verificationStatus(SellerVerificationStatus.VERIFIED)
                .build();
    }

    private SellerPayoutAccount account(SellerPayoutAccountStatus status) {
        return SellerPayoutAccount.builder()
                .payoutAccountId("PACCT-1")
                .sellerId("SELLER-1")
                .accountRef("vault://accounts/seller-1")
                .bankCode("088")
                .bankName("Shinhan Bank")
                .accountHolderName("Every Seller")
                .accountLast4("1234")
                .status(status)
                .submittedAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .build();
    }
}

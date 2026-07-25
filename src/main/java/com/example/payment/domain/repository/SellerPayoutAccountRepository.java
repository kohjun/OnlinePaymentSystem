package com.example.payment.domain.repository;

import com.example.payment.domain.model.marketplace.SellerPayoutAccount;
import com.example.payment.domain.model.marketplace.SellerPayoutAccountStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SellerPayoutAccountRepository extends JpaRepository<SellerPayoutAccount, String> {

    Optional<SellerPayoutAccount> findBySellerId(String sellerId);

    boolean existsBySellerIdAndStatus(String sellerId, SellerPayoutAccountStatus status);

    long countByStatus(SellerPayoutAccountStatus status);

    List<SellerPayoutAccount> findByStatusOrderBySubmittedAtAsc(SellerPayoutAccountStatus status, Pageable pageable);
}

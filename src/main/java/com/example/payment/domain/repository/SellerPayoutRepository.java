package com.example.payment.domain.repository;

import com.example.payment.domain.model.marketplace.SellerPayout;
import com.example.payment.domain.model.marketplace.SellerPayoutStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SellerPayoutRepository extends JpaRepository<SellerPayout, String> {
    List<SellerPayout> findBySellerIdAndStatusOrderByCreatedAtDesc(String sellerId, SellerPayoutStatus status);

    List<SellerPayout> findBySellerIdOrderByCreatedAtDesc(String sellerId);

    Optional<SellerPayout> findByPayoutIdAndSellerId(String payoutId, String sellerId);

    boolean existsBySourceTypeAndSourceId(String sourceType, String sourceId);
}

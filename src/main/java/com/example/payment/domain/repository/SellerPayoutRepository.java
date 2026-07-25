package com.example.payment.domain.repository;

import com.example.payment.domain.model.marketplace.SellerPayout;
import com.example.payment.domain.model.marketplace.SellerPayoutStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

@Repository
public interface SellerPayoutRepository extends JpaRepository<SellerPayout, String> {
    List<SellerPayout> findBySellerIdAndStatusOrderByCreatedAtDesc(String sellerId, SellerPayoutStatus status);

    List<SellerPayout> findBySellerIdOrderByCreatedAtDesc(String sellerId);

    Optional<SellerPayout> findByPayoutIdAndSellerId(String payoutId, String sellerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select payout from SellerPayout payout where payout.payoutId = :payoutId")
    Optional<SellerPayout> findByPayoutIdForUpdate(@Param("payoutId") String payoutId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select payout from SellerPayout payout where payout.payoutId = :payoutId and payout.sellerId = :sellerId")
    Optional<SellerPayout> findByPayoutIdAndSellerIdForUpdate(
            @Param("payoutId") String payoutId,
            @Param("sellerId") String sellerId);

    Optional<SellerPayout> findBySourceTypeAndSourceId(String sourceType, String sourceId);

    boolean existsBySourceTypeAndSourceId(String sourceType, String sourceId);

    long countByStatus(SellerPayoutStatus status);

    List<SellerPayout> findByStatusOrderByCreatedAtAsc(SellerPayoutStatus status, Pageable pageable);
}

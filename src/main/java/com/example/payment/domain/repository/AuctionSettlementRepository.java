package com.example.payment.domain.repository;

import com.example.payment.domain.model.marketplace.AuctionSettlement;
import com.example.payment.domain.model.marketplace.AuctionSettlementStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;
import java.time.LocalDateTime;

@Repository
public interface AuctionSettlementRepository extends JpaRepository<AuctionSettlement, String> {
    Optional<AuctionSettlement> findFirstBySaleEventIdOrderByOfferSequenceDesc(String saleEventId);

    Optional<AuctionSettlement> findFirstBySaleEventIdAndCustomerIdAndStatusOrderByOfferSequenceDesc(
            String saleEventId,
            String customerId,
            AuctionSettlementStatus status
    );

    Optional<AuctionSettlement> findFirstBySaleEventIdAndCustomerIdOrderByOfferSequenceDesc(
            String saleEventId,
            String customerId
    );

    List<AuctionSettlement> findBySaleEventIdOrderByOfferSequenceAsc(String saleEventId);

    List<AuctionSettlement> findByStatusAndCheckoutExpiresAtBefore(
            AuctionSettlementStatus status, LocalDateTime expiresAt);

    void deleteBySaleEventId(String saleEventId);
}

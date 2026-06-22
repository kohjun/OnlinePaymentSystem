package com.example.payment.domain.repository;

import com.example.payment.domain.model.marketplace.AuctionSettlement;
import com.example.payment.domain.model.marketplace.AuctionSettlementStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AuctionSettlementRepository extends JpaRepository<AuctionSettlement, String> {
    Optional<AuctionSettlement> findBySaleEventId(String saleEventId);

    Optional<AuctionSettlement> findBySaleEventIdAndCustomerIdAndStatus(
            String saleEventId,
            String customerId,
            AuctionSettlementStatus status
    );
}

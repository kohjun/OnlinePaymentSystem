package com.example.payment.domain.repository;

import com.example.payment.domain.model.marketplace.AuctionBid;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AuctionBidRepository extends JpaRepository<AuctionBid, String> {
    Optional<AuctionBid> findFirstBySaleEventIdOrderByBidAmountDescCreatedAtAsc(String saleEventId);

    List<AuctionBid> findTop10BySaleEventIdOrderByBidAmountDescCreatedAtAsc(String saleEventId);
}

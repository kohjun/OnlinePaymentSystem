package com.example.payment.domain.repository;

import com.example.payment.domain.model.marketplace.RaffleEntry;
import com.example.payment.domain.model.marketplace.RaffleEntryStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RaffleEntryRepository extends JpaRepository<RaffleEntry, String> {
    Optional<RaffleEntry> findBySaleEventIdAndCustomerId(String saleEventId, String customerId);

    List<RaffleEntry> findBySaleEventIdAndStatusOrderByCreatedAtAsc(String saleEventId, RaffleEntryStatus status);

    long countBySaleEventId(String saleEventId);

    long countBySaleEventIdAndStatus(String saleEventId, RaffleEntryStatus status);
}

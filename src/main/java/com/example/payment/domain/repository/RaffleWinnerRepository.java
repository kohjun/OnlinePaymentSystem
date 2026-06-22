package com.example.payment.domain.repository;

import com.example.payment.domain.model.marketplace.RaffleCheckoutStatus;
import com.example.payment.domain.model.marketplace.RaffleWinner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RaffleWinnerRepository extends JpaRepository<RaffleWinner, String> {
    List<RaffleWinner> findBySaleEventIdOrderByCreatedAtAsc(String saleEventId);

    Optional<RaffleWinner> findBySaleEventIdAndCustomerId(String saleEventId, String customerId);

    long countBySaleEventId(String saleEventId);

    long countBySaleEventIdAndCheckoutStatus(String saleEventId, RaffleCheckoutStatus checkoutStatus);
}

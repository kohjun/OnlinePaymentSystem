package com.example.payment.domain.repository;

import com.example.payment.domain.model.account.BuyerProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BuyerProfileRepository extends JpaRepository<BuyerProfile, String> {
    Optional<BuyerProfile> findByCustomerId(String customerId);
}

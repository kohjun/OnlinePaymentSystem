package com.example.payment.domain.repository;

import com.example.payment.domain.model.marketplace.MarketplaceOrderCancellation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MarketplaceOrderCancellationRepository
        extends JpaRepository<MarketplaceOrderCancellation, String> {
    Optional<MarketplaceOrderCancellation> findByMarketplaceOrderId(String marketplaceOrderId);
}

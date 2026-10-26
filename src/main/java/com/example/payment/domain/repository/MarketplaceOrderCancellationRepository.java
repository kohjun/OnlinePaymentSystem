package com.example.payment.domain.repository;

import com.example.payment.domain.model.marketplace.MarketplaceOrderCancellation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MarketplaceOrderCancellationRepository
        extends JpaRepository<MarketplaceOrderCancellation, String> {
    @org.springframework.data.jpa.repository.Query("select c.marketplaceOrderId from MarketplaceOrderCancellation c where c.cancellationId = :id")
    Optional<String> findOrderId(@org.springframework.data.repository.query.Param("id") String id);
    Optional<MarketplaceOrderCancellation> findByMarketplaceOrderId(String marketplaceOrderId);
    Optional<MarketplaceOrderCancellation> findByPaymentId(String paymentId);
    java.util.List<MarketplaceOrderCancellation> findByStatusNotOrderByUpdatedAtAsc(
            com.example.payment.domain.model.marketplace.OrderCancellationStatus status,
            org.springframework.data.domain.Pageable pageable);
}

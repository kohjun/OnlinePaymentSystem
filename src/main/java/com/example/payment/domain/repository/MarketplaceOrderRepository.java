package com.example.payment.domain.repository;

import com.example.payment.domain.model.marketplace.MarketplaceOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MarketplaceOrderRepository extends JpaRepository<MarketplaceOrder, String> {

    List<MarketplaceOrder> findByCustomerIdOrderByCreatedAtDesc(String customerId);

    List<MarketplaceOrder> findBySellerIdOrderByCreatedAtDesc(String sellerId);

    Optional<MarketplaceOrder> findByOrderId(String orderId);

    Optional<MarketplaceOrder> findByWorkflowIdAndCustomerId(String workflowId, String customerId);

    Optional<MarketplaceOrder> findByMarketplaceOrderIdAndSellerId(String marketplaceOrderId, String sellerId);
}

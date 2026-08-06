package com.example.payment.domain.repository;

import com.example.payment.domain.model.marketplace.MarketplaceOrder;
import com.example.payment.domain.model.marketplace.MarketplaceOrderStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Collection;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface MarketplaceOrderRepository extends JpaRepository<MarketplaceOrder, String> {

    List<MarketplaceOrder> findByCustomerIdOrderByCreatedAtDesc(String customerId);

    List<MarketplaceOrder> findByCustomerIdOrderByCreatedAtDesc(String customerId, Pageable pageable);

    List<MarketplaceOrder> findBySellerIdOrderByCreatedAtDesc(String sellerId);

    List<MarketplaceOrder> findBySellerIdOrderByCreatedAtDesc(String sellerId, Pageable pageable);

    Optional<MarketplaceOrder> findByOrderId(String orderId);

    Optional<MarketplaceOrder> findByPaymentId(String paymentId);

    Optional<MarketplaceOrder> findByWorkflowIdAndCustomerId(String workflowId, String customerId);

    Optional<MarketplaceOrder> findByMarketplaceOrderIdAndSellerId(String marketplaceOrderId, String sellerId);

    long countByDisputedAtIsNotNullAndDisputeResolvedAtIsNull();

    List<MarketplaceOrder> findByDisputedAtIsNotNullAndDisputeResolvedAtIsNullOrderByDisputedAtAsc(Pageable pageable);

    long countByStatus(MarketplaceOrderStatus status);

    List<MarketplaceOrder> findByStatusOrderByUpdatedAtAsc(MarketplaceOrderStatus status, Pageable pageable);

    @Query("""
            SELECT COALESCE(SUM(order.quantity), 0)
            FROM MarketplaceOrder order
            WHERE order.saleEventId = :saleEventId
              AND order.customerId = :customerId
              AND order.status IN :statuses
            """)
    long sumCompletedQuantityForCustomer(
            @Param("saleEventId") String saleEventId,
            @Param("customerId") String customerId,
            @Param("statuses") Collection<MarketplaceOrderStatus> statuses
    );
}

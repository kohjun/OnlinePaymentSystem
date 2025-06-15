package com.example.payment.domain.repository;

import com.example.payment.domain.InventoryTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 재고 변경 이력 리포지토리
 */
@Repository
public interface InventoryTransactionRepository extends JpaRepository<InventoryTransaction, String> {

    List<InventoryTransaction> findByProductIdOrderByCreatedAtDesc(String productId);

    List<InventoryTransaction> findByReservationId(String reservationId);

    List<InventoryTransaction> findByOrderId(String orderId);

    List<InventoryTransaction> findByPaymentId(String paymentId);

    @Query("SELECT it FROM InventoryTransaction it WHERE it.productId = :productId " +
            "AND it.createdAt BETWEEN :startDate AND :endDate ORDER BY it.createdAt DESC")
    List<InventoryTransaction> findByProductIdAndDateRange(@Param("productId") String productId,
                                                           @Param("startDate") LocalDateTime startDate,
                                                           @Param("endDate") LocalDateTime endDate);

    @Query("SELECT it.transactionType, COUNT(it) FROM InventoryTransaction it " +
            "WHERE it.productId = :productId GROUP BY it.transactionType")
    List<Object[]> getTransactionSummaryByProduct(@Param("productId") String productId);
}

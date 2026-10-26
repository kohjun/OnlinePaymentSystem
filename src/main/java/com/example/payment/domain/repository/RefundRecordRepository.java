package com.example.payment.domain.repository;

import com.example.payment.domain.entity.RefundRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;

@Repository
public interface RefundRecordRepository extends JpaRepository<RefundRecord, String> {
    java.util.List<RefundRecord> findByPaymentIdAndStatusInOrderByCreatedAtAsc(String paymentId, java.util.Collection<String> statuses);
    java.util.List<RefundRecord> findByStatusInOrderByUpdatedAtAsc(java.util.Collection<String> statuses, org.springframework.data.domain.Pageable pageable);

    Optional<RefundRecord> findByPaymentIdAndIdempotencyKey(String paymentId, String idempotencyKey);

    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM RefundRecord r WHERE r.paymentId = :paymentId AND r.status = 'SUCCEEDED'")
    BigDecimal sumSucceededAmountByPaymentId(@Param("paymentId") String paymentId);
}

package com.example.payment.domain.repository;

import com.example.payment.domain.entity.RefundRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RefundRecordRepository extends JpaRepository<RefundRecord, String> {

    Optional<RefundRecord> findByPaymentIdAndIdempotencyKey(String paymentId, String idempotencyKey);
}

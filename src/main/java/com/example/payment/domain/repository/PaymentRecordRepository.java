package com.example.payment.domain.repository;

import com.example.payment.domain.entity.PaymentRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRecordRepository extends JpaRepository<PaymentRecord, String> {
    Optional<PaymentRecord> findByReservationId(String reservationId);

    Optional<PaymentRecord> findByTransactionId(String transactionId);

    List<PaymentRecord> findByCustomerIdOrderByCreatedAtDesc(String customerId, Pageable pageable);

    long countByStatus(String status);
}

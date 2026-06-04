package com.example.payment.domain.repository;

import com.example.payment.domain.entity.PaymentRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PaymentRecordRepository extends JpaRepository<PaymentRecord, String> {
    Optional<PaymentRecord> findByReservationId(String reservationId);
}

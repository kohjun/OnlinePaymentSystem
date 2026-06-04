package com.example.payment.domain.repository;

import com.example.payment.domain.entity.OrderRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OrderRecordRepository extends JpaRepository<OrderRecord, String> {
    Optional<OrderRecord> findByReservationId(String reservationId);
}

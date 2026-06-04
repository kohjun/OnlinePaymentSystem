package com.example.payment.domain.repository;

import com.example.payment.domain.entity.InventoryReservationRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface InventoryReservationRecordRepository extends JpaRepository<InventoryReservationRecord, String> {
    Optional<InventoryReservationRecord> findByReservationId(String reservationId);
}

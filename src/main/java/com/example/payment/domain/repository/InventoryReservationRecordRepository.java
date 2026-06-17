package com.example.payment.domain.repository;

import com.example.payment.domain.entity.InventoryReservationRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface InventoryReservationRecordRepository extends JpaRepository<InventoryReservationRecord, String> {
    Optional<InventoryReservationRecord> findByReservationId(String reservationId);

    List<InventoryReservationRecord> findByCustomerIdOrderByCreatedAtDesc(String customerId, Pageable pageable);

    List<InventoryReservationRecord> findByCustomerIdAndStatusInOrderByCreatedAtDesc(
            String customerId,
            Collection<String> statuses,
            Pageable pageable
    );

    List<InventoryReservationRecord> findByProductId(String productId);

    boolean existsBySeatIdAndStatus(String seatId, String status);

    List<InventoryReservationRecord> findByStatusAndExpiresAtBefore(String status, java.time.LocalDateTime time);

    long countByProductId(String productId);

    long countByProductIdAndStatus(String productId, String status);

    long countByStatus(String status);
}

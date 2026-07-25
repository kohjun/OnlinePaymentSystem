package com.example.payment.domain.repository;

import com.example.payment.domain.model.marketplace.RaffleEntry;
import com.example.payment.domain.model.marketplace.RaffleEntryStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface RaffleEntryRepository extends JpaRepository<RaffleEntry, String> {
    Optional<RaffleEntry> findBySaleEventIdAndCustomerId(String saleEventId, String customerId);

    @Modifying
    @Query(value = """
            INSERT INTO raffle_entries(entry_id, sale_event_id, customer_id, status, created_at, updated_at)
            VALUES (:entryId, :saleEventId, :customerId, :status, :createdAt, :updatedAt)
            ON CONFLICT (sale_event_id, customer_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("entryId") String entryId,
                       @Param("saleEventId") String saleEventId,
                       @Param("customerId") String customerId,
                       @Param("status") String status,
                       @Param("createdAt") LocalDateTime createdAt,
                       @Param("updatedAt") LocalDateTime updatedAt);

    List<RaffleEntry> findBySaleEventIdAndStatusOrderByCreatedAtAsc(String saleEventId, RaffleEntryStatus status);

    long countBySaleEventId(String saleEventId);

    long countBySaleEventIdAndStatus(String saleEventId, RaffleEntryStatus status);

    void deleteBySaleEventId(String saleEventId);
}

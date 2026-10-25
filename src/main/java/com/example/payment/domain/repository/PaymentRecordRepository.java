package com.example.payment.domain.repository;

import com.example.payment.domain.entity.PaymentRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRecordRepository extends JpaRepository<PaymentRecord, String> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select p from PaymentRecord p where p.paymentId = :id")
    Optional<PaymentRecord> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") String id);

    Optional<PaymentRecord> findByReservationId(String reservationId);

    Optional<PaymentRecord> findByTransactionId(String transactionId);

    @org.springframework.data.jpa.repository.Query("""
            select p from PaymentRecord p where p.status in ('REFUNDED', 'PARTIALLY_REFUNDED')
            and exists (select m.marketplaceOrderId from MarketplaceOrder m
                where m.paymentId = p.paymentId and cast(m.status as string) <> p.status)
            order by p.updatedAt asc
            """)
    List<PaymentRecord> findRefundsNeedingFinalization(Pageable pageable);

    List<PaymentRecord> findByCustomerIdOrderByCreatedAtDesc(String customerId, Pageable pageable);

    long countByStatus(String status);

    List<PaymentRecord> findByStatusOrderByCreatedAtAsc(String status, Pageable pageable);
}

package com.example.payment.domain.repository;

import com.example.payment.domain.entity.PaymentIdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PaymentIdempotencyRepository extends JpaRepository<PaymentIdempotencyRecord, String> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select p from PaymentIdempotencyRecord p where p.tenantId = :tenant and p.merchantId = :merchant and p.operation = :operation and p.idempotencyKey = :key")
    Optional<PaymentIdempotencyRecord> lockByScope(String tenant, String merchant, String operation, String key);

    Optional<PaymentIdempotencyRecord> findByTenantIdAndMerchantIdAndOperationAndIdempotencyKey(
            String tenantId,
            String merchantId,
            String operation,
            String idempotencyKey
    );
}

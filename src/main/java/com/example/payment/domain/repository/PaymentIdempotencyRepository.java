package com.example.payment.domain.repository;

import com.example.payment.domain.entity.PaymentIdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PaymentIdempotencyRepository extends JpaRepository<PaymentIdempotencyRecord, String> {

    Optional<PaymentIdempotencyRecord> findByTenantIdAndMerchantIdAndOperationAndIdempotencyKey(
            String tenantId,
            String merchantId,
            String operation,
            String idempotencyKey
    );
}

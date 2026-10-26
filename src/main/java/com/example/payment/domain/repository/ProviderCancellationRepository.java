package com.example.payment.domain.repository;

import com.example.payment.domain.entity.ProviderCancellationRecord;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.util.Optional;

public interface ProviderCancellationRepository extends JpaRepository<ProviderCancellationRecord, String> {
    Optional<ProviderCancellationRecord> findByGatewayNameAndTransactionKey(String gatewayName, String transactionKey);
    @Query("select coalesce(sum(c.amount), 0) from ProviderCancellationRecord c where c.paymentId = :paymentId")
    BigDecimal sumByPaymentId(@Param("paymentId") String paymentId);
}

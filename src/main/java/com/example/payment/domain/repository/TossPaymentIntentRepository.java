package com.example.payment.domain.repository;

import com.example.payment.domain.entity.TossPaymentIntent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface TossPaymentIntentRepository extends JpaRepository<TossPaymentIntent, String> {
    Optional<TossPaymentIntent> findByIdempotencyKey(String idempotencyKey);

    Optional<TossPaymentIntent> findByOrderId(String orderId);

    Optional<TossPaymentIntent> findByWorkflowId(String workflowId);

    @Query("""
            SELECT t FROM TossPaymentIntent t
            WHERE t.status IN :statuses
              AND (
                t.updatedAt < :cutoff
                OR (t.updatedAt IS NULL AND t.createdAt < :cutoff)
              )
            """)
    List<TossPaymentIntent> findRecoverableIntents(
            @Param("statuses") Collection<String> statuses,
            @Param("cutoff") LocalDateTime cutoff
    );
}

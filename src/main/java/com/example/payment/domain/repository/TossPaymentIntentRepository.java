package com.example.payment.domain.repository;

import com.example.payment.domain.entity.TossPaymentIntent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TossPaymentIntentRepository extends JpaRepository<TossPaymentIntent, String> {
    Optional<TossPaymentIntent> findByIdempotencyKey(String idempotencyKey);

    Optional<TossPaymentIntent> findByOrderId(String orderId);
}

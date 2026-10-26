package com.example.payment.application.service;

import com.example.payment.domain.entity.PaymentIdempotencyRecord;
import com.example.payment.domain.repository.PaymentIdempotencyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IdempotencyClaimWriter {
    private final PaymentIdempotencyRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(PaymentIdempotencyRecord record) {
        repository.saveAndFlush(record);
    }
}

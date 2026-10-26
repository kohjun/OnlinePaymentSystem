package com.example.payment.application.service;

import com.example.payment.domain.repository.PaymentRecordRepository;
import com.example.payment.domain.repository.InventorySyncIssueRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.data.domain.PageRequest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;

@Component @Slf4j
public class PaymentRecoveryMetrics {
    private final PaymentRecordRepository payments;
    private final InventorySyncIssueRepository inventory;
    private final AtomicLong count = new AtomicLong();
    private final AtomicLong oldest = new AtomicLong();
    private final AtomicLong syncDelay = new AtomicLong();
    public PaymentRecoveryMetrics(PaymentRecordRepository payments, InventorySyncIssueRepository inventory, MeterRegistry registry) {
        this.payments = payments; this.inventory = inventory;
        registry.gauge("everysale.payment.recovery.pending", count);
        registry.gauge("everysale.payment.recovery.oldest.seconds", oldest);
        registry.gauge("everysale.inventory.sync.oldest.seconds", syncDelay);
    }
    @Scheduled(fixedDelayString = "${payment.recovery.metrics-delay-ms:60000}")
    public void refresh() {
        long total = 0, maxAge = 0;
        for (String status : Set.of("PROCESSING", "UNKNOWN", "REFUND_UNKNOWN", "REFUND_FAILED")) {
            total += payments.countByStatus(status);
            for (var payment : payments.findByStatusOrderByCreatedAtAsc(status, PageRequest.of(0, 1))) {
                maxAge = Math.max(maxAge, Duration.between(payment.getCreatedAt(), LocalDateTime.now()).getSeconds());
            }
        }
        count.set(total); oldest.set(maxAge);
        var issues = inventory.findByStatusOrderByCreatedAtAsc("PENDING", PageRequest.of(0, 1));
        syncDelay.set(issues.isEmpty() ? 0 : Math.max(0, Duration.between(issues.get(0).getCreatedAt(), LocalDateTime.now()).getSeconds()));
        if (maxAge >= 1800) log.warn("Payment recovery needs operator review: count={}, oldestSeconds={}", total, maxAge);
    }
}

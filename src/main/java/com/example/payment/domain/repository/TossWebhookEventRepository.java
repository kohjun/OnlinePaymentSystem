package com.example.payment.domain.repository;

import com.example.payment.domain.entity.TossWebhookEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface TossWebhookEventRepository extends JpaRepository<TossWebhookEvent, String> {
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update TossWebhookEvent e set e.processingStatus = 'PENDING', e.leaseToken = null, e.leaseUntil = null where e.processingStatus = 'PROCESSING' and (e.leaseUntil < :now or e.leaseUntil is null)")
    int recoverStale(@org.springframework.data.repository.query.Param("now") java.time.LocalDateTime now);

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update TossWebhookEvent e set e.processingStatus = 'PROCESSING', e.attemptCount = e.attemptCount + 1, e.leaseToken = :token, e.leaseUntil = :until where e.eventId = :id and e.processingStatus in ('PENDING', 'FAILED')")
    int claim(@org.springframework.data.repository.query.Param("id") String id, @org.springframework.data.repository.query.Param("token") String token, @org.springframework.data.repository.query.Param("until") java.time.LocalDateTime until);

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update TossWebhookEvent e set e.processingStatus = :status, e.failureReason = :reason, e.processedAt = :now, e.leaseToken = null, e.leaseUntil = null where e.eventId = :id and e.leaseToken = :token")
    int finish(@org.springframework.data.repository.query.Param("id") String id, @org.springframework.data.repository.query.Param("token") String token, @org.springframework.data.repository.query.Param("status") String status, @org.springframework.data.repository.query.Param("reason") String reason, @org.springframework.data.repository.query.Param("now") java.time.LocalDateTime now);
    Optional<TossWebhookEvent> findByDedupeKey(String dedupeKey);

    List<TossWebhookEvent> findByProcessingStatusInAndAttemptCountLessThanOrderByReceivedAtAsc(
            Collection<String> statuses,
            Integer maxAttemptCount,
            Pageable pageable
    );
}

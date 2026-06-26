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
    Optional<TossWebhookEvent> findByDedupeKey(String dedupeKey);

    List<TossWebhookEvent> findByProcessingStatusInAndAttemptCountLessThanOrderByReceivedAtAsc(
            Collection<String> statuses,
            Integer maxAttemptCount,
            Pageable pageable
    );
}

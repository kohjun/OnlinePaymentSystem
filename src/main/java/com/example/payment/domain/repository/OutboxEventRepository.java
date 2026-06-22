package com.example.payment.domain.repository;

import com.example.payment.domain.entity.OutboxEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {
    List<OutboxEvent> findTop50ByStatusOrderByCreatedAtAsc(String status);
    List<OutboxEvent> findTop50ByStatusInOrderByCreatedAtAsc(Collection<String> statuses);
    List<OutboxEvent> findByStatusInOrderByCreatedAtAsc(Collection<String> statuses, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from OutboxEvent e where e.eventId = :eventId")
    Optional<OutboxEvent> findForUpdateByEventId(@Param("eventId") String eventId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update OutboxEvent e
               set e.status = 'PENDING',
                   e.lockedAt = null,
                   e.lastError = :reason,
                   e.nextAttemptAt = :nextAttemptAt
             where e.status = 'IN_PROGRESS'
               and e.lockedAt is not null
               and e.lockedAt < :staleBefore
            """)
    int resetStaleInProgressEvents(@Param("staleBefore") LocalDateTime staleBefore,
                                   @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                                   @Param("reason") String reason);
}

package com.example.payment.domain.repository;

import com.example.payment.domain.entity.SecurityAuditEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SecurityAuditEventRepository extends JpaRepository<SecurityAuditEvent, String> {
    List<SecurityAuditEvent> findByActionStartingWithOrderByCreatedAtDesc(String actionPrefix, Pageable pageable);

    List<SecurityAuditEvent> findByActionStartingWithAndResourceTypeOrderByCreatedAtDesc(
            String actionPrefix,
            String resourceType,
            Pageable pageable
    );

    List<SecurityAuditEvent> findByActionStartingWithAndResourceTypeAndResourceIdOrderByCreatedAtDesc(
            String actionPrefix,
            String resourceType,
            String resourceId,
            Pageable pageable
    );
}

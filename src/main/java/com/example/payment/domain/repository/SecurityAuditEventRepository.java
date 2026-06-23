package com.example.payment.domain.repository;

import com.example.payment.domain.entity.SecurityAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SecurityAuditEventRepository extends JpaRepository<SecurityAuditEvent, String> {
}
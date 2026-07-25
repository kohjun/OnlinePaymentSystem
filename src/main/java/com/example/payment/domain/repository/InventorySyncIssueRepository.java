package com.example.payment.domain.repository;

import com.example.payment.domain.entity.InventorySyncIssue;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InventorySyncIssueRepository extends JpaRepository<InventorySyncIssue, String> {

    List<InventorySyncIssue> findByStatusOrderByCreatedAtAsc(String status, Pageable pageable);
}

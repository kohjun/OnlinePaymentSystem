/**
 * 간단한 WAL JPA Repository
 * 파일 위치: src/main/java/com/example/payment/infrastructure/persistence/jpa/WalLogJpaRepository.java
 */
package com.example.payment.infrastructure.persistence.jpa;

import com.example.payment.domain.entity.WalLogEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface WalLogJpaRepository extends JpaRepository<WalLogEntry, String> {

    /**
     * 상태별 로그 조회 (LSN 순서)
     */
    List<WalLogEntry> findByStatusInOrderByLsnAsc(List<String> statuses);

    /**
     * 트랜잭션별 로그 조회 (LSN 순서)
     */
    List<WalLogEntry> findByTransactionIdOrderByLsnAsc(String transactionId);

    /**
     * 완료된 오래된 로그 조회 (정리용)
     */
    @Query("SELECT w FROM WalLogEntry w WHERE w.status IN ('COMMITTED', 'FAILED') AND w.completedAt < :before")
    List<WalLogEntry> findCompletedLogsBefore(@Param("before") LocalDateTime before);

    /**
     * 로그 ID 목록으로 삭제
     */
    void deleteByLogIdIn(List<String> logIds);

    /**
     * 상태별 로그 개수 조회
     */
    long countByStatus(String status);

    /**
     * 미완료 로그 조회 (체크용)
     */
    @Query("SELECT w FROM WalLogEntry w WHERE w.status IN ('PENDING', 'IN_PROGRESS') AND w.createdAt < :threshold ORDER BY w.createdAt ASC")
    List<WalLogEntry> findStuckTransactions(@Param("threshold") LocalDateTime threshold);
}
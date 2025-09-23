/**
 * WAL JPA Repository (수정된 버전)
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
     * 특정 LSN 이후의 로그 조회 (복구용)
     */
    @Query("SELECT w FROM WalLogEntry w WHERE w.lsn > :checkpointLsn ORDER BY w.lsn ASC")
    List<WalLogEntry> findByLsnGreaterThanOrderByLsnAsc(@Param("checkpointLsn") Long checkpointLsn);

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

    /**
     * 다음 LSN 생성 (시퀀스 기반)
     * H2 데이터베이스용 구현
     */
    @Query(value = "SELECT NEXTVAL('wal_lsn_sequence')", nativeQuery = true)
    Long getNextLSN();

    /**
     * 최대 LSN 조회 (체크포인트용)
     */
    @Query("SELECT MAX(w.lsn) FROM WalLogEntry w")
    Long findMaxLsn();

    /**
     * 연산별 로그 개수 조회 (통계용)
     */
    @Query("SELECT w.operation, COUNT(w) FROM WalLogEntry w GROUP BY w.operation")
    List<Object[]> getOperationStatistics();

    /**
     * 최근 로그 조회 (모니터링용)
     */
    @Query("SELECT w FROM WalLogEntry w ORDER BY w.createdAt DESC")
    List<WalLogEntry> findRecentLogs(org.springframework.data.domain.Pageable pageable);
}
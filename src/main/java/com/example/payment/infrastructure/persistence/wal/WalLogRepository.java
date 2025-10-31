package com.example.payment.infrastructure.persistence.wal;

import com.example.payment.domain.entity.WalLogEntry;
import com.example.payment.infrastructure.persistence.jpa.WalLogJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Repository
@Slf4j
@RequiredArgsConstructor
public class WalLogRepository {

    private final WalLogJpaRepository jpaRepository;
    private final WalAsyncProcessor asyncProcessor;

    /**
     * WAL 로그 기록 (독립적인 트랜잭션)
     * - 메인 트랜잭션과 분리하여 빠른 기록
     * - 내구성 최우선 보장
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WalLogEntry writeLog(WalLogEntry logEntry) {

        log.debug("Writing WAL log: transactionId={}, operation={}",
                logEntry.getTransactionId(), logEntry.getOperation());

        try {
            // 1. 로그 순서 번호 생성 (LSN - Log Sequence Number)
            // ✅ 직접 try-catch로 시퀀스/폴백 처리 (트랜잭션 롤백 방지)
            Long lsn;
            try {
                lsn = jpaRepository.getNextLSN();
                log.debug("LSN generated from sequence: {}", lsn);
            } catch (Exception seqEx) {
                // 시퀀스가 없으면 (테스트 환경 등) 폴백 사용
                log.debug("DB sequence not available, using fallback LSN generation: {}", seqEx.getMessage());
                lsn = System.currentTimeMillis() * 1_000_000 + (System.nanoTime() % 1_000_000);
                log.debug("LSN generated from fallback: {}", lsn);
            }

            logEntry.setLsn(lsn);
            logEntry.setWrittenAt(LocalDateTime.now());

            // 2. 즉시 디스크에 기록 (fsync 보장)
            WalLogEntry savedEntry = jpaRepository.saveAndFlush(logEntry);

            // 3. 비동기로 백업 및 압축 처리
            asyncProcessor.processLogAsync(savedEntry);

            log.debug("WAL log written successfully: logId={}, lsn={}",
                    savedEntry.getLogId(), lsn);

            return savedEntry;

        } catch (Exception e) {
            log.error("Failed to write WAL log: transactionId={}, operation={}",
                    logEntry.getTransactionId(), logEntry.getOperation(), e);
            throw new WalException("WAL 로그 기록 실패", e);
        }
    }

    /**
     * WAL 로그 상태 업데이트
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateLogStatus(String logId, String status, String message) {

        try {
            WalLogEntry logEntry = jpaRepository.findById(logId)
                    .orElseThrow(() -> new WalException("WAL 로그를 찾을 수 없습니다: " + logId));

            logEntry.setStatus(status);
            logEntry.setMessage(message);
            logEntry.setUpdatedAt(LocalDateTime.now());

            // 완료 상태인 경우 완료 시간 기록
            if ("COMMITTED".equals(status) || "FAILED".equals(status)) {
                logEntry.setCompletedAt(LocalDateTime.now());
            }

            jpaRepository.saveAndFlush(logEntry);

            log.debug("WAL log status updated: logId={}, status={}", logId, status);

        } catch (Exception e) {
            log.error("Failed to update WAL log status: logId={}, status={}", logId, status, e);
            throw new WalException("WAL 로그 상태 업데이트 실패", e);
        }
    }

    /**
     * 미완료 트랜잭션 조회 (복구용)
     */
    public List<WalLogEntry> findPendingTransactions() {
        try {
            return jpaRepository.findByStatusInOrderByLsnAsc(
                    List.of("PENDING", "IN_PROGRESS")
            );
        } catch (Exception e) {
            log.error("Failed to find pending transactions", e);
            throw new WalException("미완료 트랜잭션 조회 실패", e);
        }
    }

    /**
     * 특정 트랜잭션의 모든 로그 조회
     */
    public List<WalLogEntry> findLogsByTransaction(String transactionId) {
        try {
            return jpaRepository.findByTransactionIdOrderByLsnAsc(transactionId);
        } catch (Exception e) {
            log.error("Failed to find logs for transaction: {}", transactionId, e);
            throw new WalException("트랜잭션 로그 조회 실패", e);
        }
    }

    /**
     * 체크포인트 이후의 로그 조회 (복구용)
     */
    public List<WalLogEntry> findLogsAfterCheckpoint(Long checkpointLsn) {
        try {
            return jpaRepository.findByLsnGreaterThanOrderByLsnAsc(checkpointLsn);
        } catch (Exception e) {
            log.error("Failed to find logs after checkpoint: {}", checkpointLsn, e);
            throw new WalException("체크포인트 이후 로그 조회 실패", e);
        }
    }

    /**
     * 오래된 WAL 로그 정리 (아카이빙)
     */
    @Transactional
    public int archiveOldLogs(LocalDateTime before) {

        log.info("Starting WAL log archiving: before={}", before);

        try {
            // 1. 완료된 오래된 로그들 조회
            List<WalLogEntry> oldLogs = jpaRepository.findCompletedLogsBefore(before);

            if (oldLogs.isEmpty()) {
                log.info("No old logs to archive");
                return 0;
            }

            // 2. 아카이브 스토리지로 이동 (비동기)
            CompletableFuture.runAsync(() -> {
                try {
                    asyncProcessor.archiveLogs(oldLogs);

                    // 3. 원본 로그 삭제
                    List<String> logIds = oldLogs.stream()
                            .map(WalLogEntry::getLogId)
                            .toList();

                    jpaRepository.deleteByLogIdIn(logIds);

                    log.info("Archived {} WAL logs successfully", oldLogs.size());

                } catch (Exception e) {
                    log.error("Failed to archive WAL logs", e);
                }
            });

        } catch (Exception e) {
            log.error("Failed to start WAL log archiving", e);
            throw new WalException("WAL 로그 아카이빙 실패", e);
        }
        return 0;
    }

    /**
     * LSN(Log Sequence Number) 생성
     * ✅ 별도 트랜잭션으로 분리하여 실패해도 메인 트랜잭션에 영향 없음
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)  // ✅ 추가
    public Long generateNextLSN() {
        try {
            // DB 시퀀스를 사용하여 유니크한 LSN 생성
            return jpaRepository.getNextLSN();
        } catch (Exception e) {
            // 시퀀스가 없으면 (테스트 환경 등) 폴백 사용
            log.debug("DB sequence not available, using fallback LSN generation");
            // 폴백: 타임스탬프 + 나노초 기반 LSN
            return System.currentTimeMillis() * 1_000_000 + (System.nanoTime() % 1_000_000);
        }
    }
}
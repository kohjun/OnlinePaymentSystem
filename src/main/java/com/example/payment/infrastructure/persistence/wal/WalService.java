package com.example.payment.infrastructure.persistence.wal;

import com.example.payment.domain.entity.WalLogEntry;
import com.example.payment.infrastructure.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * WAL 서비스 - 횡단 관심사 (Cross-Cutting Concern)
 *
 * 단일 책임: WAL 로그 기록 및 관리만 담당
 * - 로그 생성
 * - 로그 상태 업데이트
 * - 로그 조회
 *
 * 도메인 로직은 포함하지 않음!
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WalService {

    private final WalLogRepository walLogRepository;

    /**
     * 작업 시작 로그 기록 (Phase 1)
     *
     * @param operation 작업 타입 (예: "INVENTORY_RESERVE_START")
     * @param tableName 대상 테이블
     * @param afterData 변경 후 데이터 (JSON)
     * @return WAL Entry ID (상태 업데이트용)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String logOperationStart(String operation, String tableName, String afterData) {
        try {
            String transactionId = IdGenerator.generateTransactionId();
            String logId = IdGenerator.generateEventId();

            WalLogEntry logEntry = WalLogEntry.builder()
                    .logId(logId)
                    .transactionId(transactionId)
                    .operation(operation)
                    .tableName(tableName)
                    .beforeData(null)
                    .afterData(afterData)
                    .status("PENDING")
                    .message("작업 시작")
                    .createdAt(LocalDateTime.now())
                    .build();

            walLogRepository.writeLog(logEntry);

            log.debug("WAL start logged: operation={}, logId={}", operation, logId);
            return logId;

        } catch (Exception e) {
            log.error("Failed to log operation start: operation={}", operation, e);
            throw new WalException("WAL 시작 로그 기록 실패", e);
        }
    }

    /**
     * 작업 완료 로그 기록 (Phase 1 or 2)
     *
     * @param operation 작업 타입
     * @param tableName 대상 테이블
     * @param beforeData 변경 전 데이터
     * @param afterData 변경 후 데이터
     * @return WAL Entry ID
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String logOperationComplete(String operation, String tableName,
                                       String beforeData, String afterData) {
        try {
            String transactionId = IdGenerator.generateTransactionId();
            String logId = IdGenerator.generateEventId();

            WalLogEntry logEntry = WalLogEntry.builder()
                    .logId(logId)
                    .transactionId(transactionId)
                    .operation(operation)
                    .tableName(tableName)
                    .beforeData(beforeData)
                    .afterData(afterData)
                    .status("COMMITTED")
                    .message("작업 완료")
                    .createdAt(LocalDateTime.now())
                    .completedAt(LocalDateTime.now())
                    .build();

            walLogRepository.writeLog(logEntry);

            log.debug("WAL complete logged: operation={}, logId={}", operation, logId);
            return logId;

        } catch (Exception e) {
            log.error("Failed to log operation complete: operation={}", operation, e);
            throw new WalException("WAL 완료 로그 기록 실패", e);
        }
    }

    /**
     * 작업 실패 로그 기록
     *
     * @param operation 작업 타입
     * @param tableName 대상 테이블
     * @param errorMessage 오류 메시지
     * @return WAL Entry ID
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String logOperationFailure(String operation, String tableName, String errorMessage) {
        try {
            String transactionId = IdGenerator.generateTransactionId();
            String logId = IdGenerator.generateEventId();

            WalLogEntry logEntry = WalLogEntry.builder()
                    .logId(logId)
                    .transactionId(transactionId)
                    .operation(operation)
                    .tableName(tableName)
                    .beforeData(null)
                    .afterData(String.format("{\"error\":\"%s\"}", errorMessage))
                    .status("FAILED")
                    .message("작업 실패: " + errorMessage)
                    .createdAt(LocalDateTime.now())
                    .completedAt(LocalDateTime.now())
                    .build();

            walLogRepository.writeLog(logEntry);

            log.warn("WAL failure logged: operation={}, error={}", operation, errorMessage);
            return logId;

        } catch (Exception e) {
            log.error("Failed to log operation failure: operation={}", operation, e);
            // WAL 실패 로그 기록도 실패하면 심각한 상황
            return null;
        }
    }

    /**
     * 로그 상태 업데이트
     *
     * @param logId WAL Entry ID
     * @param status 새 상태
     * @param message 메시지
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateLogStatus(String logId, String status, String message) {
        try {
            walLogRepository.updateLogStatus(logId, status, message);
            log.debug("WAL status updated: logId={}, status={}", logId, status);

        } catch (Exception e) {
            log.error("Failed to update WAL status: logId={}, status={}", logId, status, e);
            throw new WalException("WAL 상태 업데이트 실패", e);
        }
    }

    /**
     * Phase 2 시작 로그 (확정 작업용)
     *
     * @param phase2Operation Phase 2 작업명
     * @param relatedLogId Phase 1 로그 ID (연관)
     * @param beforeData 변경 전 상태
     * @param afterData 변경 후 상태
     * @return Phase 2 WAL Entry ID
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String logPhase2Start(String phase2Operation, String relatedLogId,
                                 String beforeData, String afterData) {
        try {
            String transactionId = IdGenerator.generateTransactionId();
            String logId = IdGenerator.generateEventId();

            WalLogEntry logEntry = WalLogEntry.builder()
                    .logId(logId)
                    .transactionId(transactionId)
                    .operation(phase2Operation)
                    .tableName("phase2")
                    .beforeData(beforeData)
                    .afterData(afterData)
                    .status("PENDING")
                    .message("Phase 2 시작")
                    .relatedLogId(relatedLogId) // Phase 1과 연결
                    .createdAt(LocalDateTime.now())
                    .build();

            walLogRepository.writeLog(logEntry);

            log.debug("WAL Phase 2 start logged: operation={}, logId={}, relatedLogId={}",
                    phase2Operation, logId, relatedLogId);
            return logId;

        } catch (Exception e) {
            log.error("Failed to log Phase 2 start: operation={}", phase2Operation, e);
            throw new WalException("WAL Phase 2 시작 로그 기록 실패", e);
        }
    }

    /**
     * 트랜잭션 ID 생성 (외부 호출용)
     */
    public String generateTransactionId() {
        return IdGenerator.generateTransactionId();
    }

    /**
     * 복구용 로그 조회
     */
    public java.util.List<WalLogEntry> findPendingLogs() {
        return walLogRepository.findPendingTransactions();
    }

}
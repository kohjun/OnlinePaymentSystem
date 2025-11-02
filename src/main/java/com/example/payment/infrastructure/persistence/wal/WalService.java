package com.example.payment.infrastructure.persistence.wal;

import com.example.payment.domain.entity.WalLogEntry;
import com.example.payment.infrastructure.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ✅ 개선된 WAL 서비스 - 트랜잭션 정합성 강화
 *
 * 주요 개선사항:
 * 1. 트랜잭션 ID를 외부에서 주입받아 일관성 보장
 * 2. Phase 1과 Phase 2의 명확한 연결 (related_log_id 활용)
 * 3. 데이터 ID(reservationId, orderId, paymentId) 추적 강화
 * 4. 트랜잭션 체인 추적을 위한 메타데이터 개선
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WalService {

    private final WalLogRepository walLogRepository;

    /**
     * ✅ 개선: 트랜잭션 ID를 받아서 시작 로그 기록 (Phase 1)
     *
     * 장점:
     * - 같은 비즈니스 트랜잭션의 모든 WAL 로그가 동일한 transactionId 공유
     * - 분산 추적(Distributed Tracing) 가능
     *
     * @param transactionId 비즈니스 트랜잭션 ID (correlationId)
     * @param operation 작업 타입
     * @param tableName 대상 테이블
     * @param entityIds 엔티티 ID들 (JSON: {"reservationId":"xxx", "orderId":"yyy"})
     * @param afterData 변경 후 데이터
     * @return WAL Entry ID
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String logOperationStart(String transactionId, String operation,
                                    String tableName, String entityIds, String afterData) {
        try {
            String logId = IdGenerator.generateEventId();

            WalLogEntry logEntry = WalLogEntry.builder()
                    .logId(logId)
                    .transactionId(transactionId)  // ✅ 외부에서 주입받은 트랜잭션 ID
                    .operation(operation)
                    .tableName(tableName)
                    .beforeData(entityIds)  // ✅ 엔티티 ID 추적용
                    .afterData(afterData)
                    .status("PENDING")
                    .message("Phase 1: " + operation + " 시작")
                    .createdAt(LocalDateTime.now())
                    .build();

            walLogRepository.writeLog(logEntry);

            log.debug("✅ WAL Phase 1 logged: txId={}, operation={}, logId={}, entities={}",
                    transactionId, operation, logId, entityIds);
            return logId;

        } catch (Exception e) {
            log.error("❌ Failed to log operation start: txId={}, operation={}",
                    transactionId, operation, e);
            throw new WalException("WAL 시작 로그 기록 실패", e);
        }
    }

    /**
     * ✅ 개선: Phase 2 시작 로그 (확정 작업용)
     *
     * Phase 1 로그와 명확하게 연결됨
     *
     * @param transactionId 비즈니스 트랜잭션 ID
     * @param phase1LogId Phase 1의 WAL Entry ID (연결용)
     * @param operation Phase 2 작업명
     * @param tableName 대상 테이블
     * @param entityIds 엔티티 ID들
     * @param beforeData 변경 전 상태
     * @param afterData 변경 후 상태
     * @return Phase 2 WAL Entry ID
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String logPhase2Start(String transactionId, String phase1LogId,
                                 String operation, String tableName, String entityIds,
                                 String beforeData, String afterData) {
        try {
            String logId = IdGenerator.generateEventId();

            WalLogEntry logEntry = WalLogEntry.builder()
                    .logId(logId)
                    .transactionId(transactionId)  // ✅ Phase 1과 동일한 트랜잭션 ID
                    .operation(operation)
                    .tableName(tableName)
                    .beforeData(entityIds + "|" + beforeData)  // ✅ 엔티티 ID + 상태
                    .afterData(afterData)
                    .status("PENDING")
                    .message("Phase 2: " + operation + " 시작")
                    .relatedLogId(phase1LogId)  // ✅ Phase 1과 명확하게 연결
                    .createdAt(LocalDateTime.now())
                    .build();

            walLogRepository.writeLog(logEntry);

            log.debug("✅ WAL Phase 2 logged: txId={}, operation={}, logId={}, phase1LogId={}, entities={}",
                    transactionId, operation, logId, phase1LogId, entityIds);
            return logId;

        } catch (Exception e) {
            log.error("❌ Failed to log Phase 2 start: txId={}, operation={}",
                    transactionId, operation, e);
            throw new WalException("WAL Phase 2 시작 로그 기록 실패", e);
        }
    }

    /**
     * ✅ 개선: 작업 완료 로그 (상태 업데이트 포함)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String logOperationComplete(String transactionId, String operation,
                                       String tableName, String entityIds,
                                       String beforeData, String afterData) {
        try {
            String logId = IdGenerator.generateEventId();

            WalLogEntry logEntry = WalLogEntry.builder()
                    .logId(logId)
                    .transactionId(transactionId)
                    .operation(operation)
                    .tableName(tableName)
                    .beforeData(entityIds + (beforeData != null ? "|" + beforeData : ""))
                    .afterData(afterData)
                    .status("COMMITTED")
                    .message("작업 완료")
                    .createdAt(LocalDateTime.now())
                    .completedAt(LocalDateTime.now())
                    .build();

            walLogRepository.writeLog(logEntry);

            log.debug("✅ WAL complete logged: txId={}, operation={}, logId={}",
                    transactionId, operation, logId);
            return logId;

        } catch (Exception e) {
            log.error("❌ Failed to log operation complete: txId={}, operation={}",
                    transactionId, operation, e);
            throw new WalException("WAL 완료 로그 기록 실패", e);
        }
    }

    /**
     *  개선: 작업 실패 로그
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String logOperationFailure(String transactionId, String operation,
                                      String tableName, String entityIds, String errorMessage) {
        try {
            String logId = IdGenerator.generateEventId();

            WalLogEntry logEntry = WalLogEntry.builder()
                    .logId(logId)
                    .transactionId(transactionId)
                    .operation(operation)
                    .tableName(tableName)
                    .beforeData(entityIds)
                    .afterData(String.format("{\"error\":\"%s\"}", errorMessage))
                    .status("FAILED")
                    .message("작업 실패: " + errorMessage)
                    .createdAt(LocalDateTime.now())
                    .completedAt(LocalDateTime.now())
                    .build();

            walLogRepository.writeLog(logEntry);

            log.warn("⚠️ WAL failure logged: txId={}, operation={}, error={}",
                    transactionId, operation, errorMessage);
            return logId;

        } catch (Exception e) {
            log.error("❌ Failed to log operation failure: txId={}, operation={}",
                    transactionId, operation, e);
            return null;
        }
    }

    /**
     *  로그 상태 업데이트
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateLogStatus(String logId, String status, String message) {
        try {
            walLogRepository.updateLogStatus(logId, status, message);
            log.debug(" WAL status updated: logId={}, status={}", logId, status);

        } catch (Exception e) {
            log.error(" Failed to update WAL status: logId={}, status={}", logId, status, e);
            throw new WalException("WAL 상태 업데이트 실패", e);
        }
    }

    /**
     *  트랜잭션 복구용: 특정 트랜잭션의 모든 로그 조회
     *
     * 장점: 같은 transactionId를 가진 모든 WAL 로그를 시간순으로 조회하여
     * 트랜잭션 전체 흐름을 파악하고 복구 가능
     */
    public List<WalLogEntry> findLogsByTransaction(String transactionId) {
        return walLogRepository.findLogsByTransaction(transactionId);
    }

    /**
     *  미완료 트랜잭션 조회 (복구용)
     */
    public List<WalLogEntry> findPendingLogs() {
        return walLogRepository.findPendingTransactions();
    }

    /**
     *  트랜잭션 ID 생성 헬퍼 (외부 호출용)
     */
    public String generateTransactionId() {
        return IdGenerator.generateTransactionId();
    }
}
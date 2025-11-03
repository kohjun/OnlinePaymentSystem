package com.example.payment.scheduler;

import com.example.payment.infrastructure.persistence.wal.WalLogRepository;
// 1. WalRecoveryService 임포트
import com.example.payment.application.service.WalRecoveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@Slf4j
@RequiredArgsConstructor
public class WalScheduledTasks {

    private final WalLogRepository walLogRepository;
    // 2. WalRecoveryService 주입
    private final WalRecoveryService walRecoveryService;

    /**
     * 오래된 WAL 로그 정리 (매일 새벽 2시)
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanupOldLogs() {
        log.info("Starting WAL log cleanup");
        try {
            LocalDateTime cutoffDate = LocalDateTime.now().minusDays(30); // 30일 이전
            int cleanedCount = walLogRepository.archiveOldLogs(cutoffDate);
            log.info("WAL log cleanup completed: cleanedCount={}", cleanedCount);
        } catch (Exception e) {
            log.error("Failed to cleanup WAL logs", e);
        }
    }

    /**
     * 미완료 트랜잭션 복구 (매 5분마다)
     * 3. 기존 checkPendingTransactions 메소드를 아래 내용으로 교체
     */
    @Scheduled(fixedRate = 300000) // 5분 (300000ms)
    public void recoverPendingTransactions() {
        log.info("[Scheduler] Starting periodic WAL recovery check...");
        try {
            // 4. 복구 서비스 호출
            walRecoveryService.recoverPendingTransactions();
            log.info("[Scheduler] Periodic WAL recovery check finished.");
        } catch (Exception e) {
            // 이 스케줄링 작업 자체는 실패하면 안 됨
            log.error("[Scheduler] Failed to run WAL recovery task", e);
        }
    }
}
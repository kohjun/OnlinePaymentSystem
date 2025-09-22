package com.example.payment.scheduler;

import com.example.payment.infrastructure.persistence.wal.WalLogRepository;
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
     * 미완료 트랜잭션 체크 (매 30분마다)
     */
    @Scheduled(fixedRate = 1800000) // 30분
    public void checkPendingTransactions() {
        try {
            var pendingTransactions = walLogRepository.findPendingTransactions();

            if (pendingTransactions.size() > 100) {
                log.warn("Too many pending transactions: count={}", pendingTransactions.size());
            }

        } catch (Exception e) {
            log.error("Failed to check pending transactions", e);
        }
    }
}
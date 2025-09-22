package com.example.payment.infrastructure.persistence.wal;

import com.example.payment.domain.entity.WalLogEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPOutputStream;

@Component
@Slf4j
@RequiredArgsConstructor
public class WalAsyncProcessor {

    private final WalArchiveService archiveService;
    private final WalMetricsService metricsService;
    private final WalBackupService backupService;

    // 압축 임계값 (1KB)
    private static final int COMPRESSION_THRESHOLD = 1024;

    /**
     * WAL 로그 비동기 후처리
     * - 로그 압축
     * - 백업 복제본 생성
     * - 메트릭 업데이트
     */
    @Async("walExecutor")
    public CompletableFuture<Void> processLogAsync(WalLogEntry logEntry) {
        return CompletableFuture.runAsync(() -> {
            try {
                log.debug("Starting async processing for WAL log: logId={}", logEntry.getLogId());

                // 1. 로그 압축 (큰 데이터인 경우)
                if (shouldCompress(logEntry)) {
                    compressLogData(logEntry);
                }

                // 2. 백업 복제본 생성
                createBackupCopy(logEntry);

                // 3. 메트릭 업데이트
                updateMetrics(logEntry);

                // 4. 인덱싱 (검색 최적화)
                updateSearchIndex(logEntry);

                log.debug("Async processing completed for WAL log: logId={}", logEntry.getLogId());

            } catch (Exception e) {
                log.error("Failed to process WAL log asynchronously: logId={}",
                        logEntry.getLogId(), e);

                // 실패 메트릭 업데이트
                metricsService.incrementAsyncProcessingFailure(logEntry.getOperation());
            }
        });
    }

    /**
     * WAL 로그 아카이빙 (배치 처리)
     */
    @Async("walExecutor")
    public CompletableFuture<Void> archiveLogs(List<WalLogEntry> logs) {
        return CompletableFuture.runAsync(() -> {
            try {
                log.info("Starting WAL log archiving: count={}", logs.size());

                // 1. 아카이브 스토리지로 이동
                archiveService.archiveToStorage(logs);

                // 2. 압축 및 최적화
                archiveService.compressArchivedLogs(logs);

                // 3. 체크섬 검증
                archiveService.validateArchivedLogs(logs);

                log.info("Successfully archived {} WAL logs", logs.size());

                // 아카이빙 메트릭 업데이트
                metricsService.updateArchivingMetrics(logs.size());

            } catch (Exception e) {
                log.error("Failed to archive WAL logs: count={}", logs.size(), e);

                // 실패 메트릭 업데이트
                metricsService.incrementArchivingFailure(logs.size());

                // 재시도 스케줄링
                scheduleRetryArchiving(logs);
            }
        });
    }

    /**
     * 압축 필요 여부 판단
     */
    private boolean shouldCompress(WalLogEntry logEntry) {
        int beforeDataSize = logEntry.getBeforeData() != null ? logEntry.getBeforeData().length() : 0;
        int afterDataSize = logEntry.getAfterData() != null ? logEntry.getAfterData().length() : 0;
        int totalSize = beforeDataSize + afterDataSize;

        boolean shouldCompress = totalSize > COMPRESSION_THRESHOLD;

        log.trace("Compression check for logId={}: totalSize={}, threshold={}, shouldCompress={}",
                logEntry.getLogId(), totalSize, COMPRESSION_THRESHOLD, shouldCompress);

        return shouldCompress;
    }

    /**
     * 로그 데이터 압축
     */
    private void compressLogData(WalLogEntry logEntry) {
        try {
            log.debug("Compressing log data for: logId={}", logEntry.getLogId());

            // Before 데이터 압축
            if (logEntry.getBeforeData() != null && !logEntry.getBeforeData().isEmpty()) {
                String compressedBefore = compressString(logEntry.getBeforeData());
                logEntry.setBeforeData(compressedBefore);
            }

            // After 데이터 압축
            if (logEntry.getAfterData() != null && !logEntry.getAfterData().isEmpty()) {
                String compressedAfter = compressString(logEntry.getAfterData());
                logEntry.setAfterData(compressedAfter);
            }

            // 압축 메트릭 업데이트
            metricsService.incrementCompressionCount(logEntry.getOperation());

            log.debug("Log data compression completed for: logId={}", logEntry.getLogId());

        } catch (Exception e) {
            log.error("Failed to compress log data: logId={}", logEntry.getLogId(), e);
            metricsService.incrementCompressionFailure(logEntry.getOperation());
        }
    }

    /**
     * 문자열 GZIP 압축
     */
    private String compressString(String data) throws IOException {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             GZIPOutputStream gzipOut = new GZIPOutputStream(bos)) {

            gzipOut.write(data.getBytes("UTF-8"));
            gzipOut.finish();

            // Base64 인코딩하여 문자열로 반환
            return java.util.Base64.getEncoder().encodeToString(bos.toByteArray());
        }
    }

    /**
     * 백업 복제본 생성
     */
    private void createBackupCopy(WalLogEntry logEntry) {
        try {
            log.debug("Creating backup copy for: logId={}", logEntry.getLogId());

            // 백업 서비스로 복제본 생성
            backupService.createBackupCopy(logEntry);

            // 백업 메트릭 업데이트
            metricsService.incrementBackupCount(logEntry.getOperation());

            log.debug("Backup copy created for: logId={}", logEntry.getLogId());

        } catch (Exception e) {
            log.error("Failed to create backup copy: logId={}", logEntry.getLogId(), e);
            metricsService.incrementBackupFailure(logEntry.getOperation());
        }
    }

    /**
     * 메트릭 업데이트
     */
    private void updateMetrics(WalLogEntry logEntry) {
        try {
            log.trace("Updating metrics for: logId={}", logEntry.getLogId());

            // 1. 처리 시간 메트릭
            long processingTime = logEntry.getProcessingDurationMs();
            metricsService.recordProcessingTime(logEntry.getOperation(), processingTime);

            // 2. 데이터 크기 메트릭
            int dataSize = calculateDataSize(logEntry);
            metricsService.recordDataSize(logEntry.getOperation(), dataSize);

            // 3. 상태별 카운터 업데이트
            metricsService.incrementStatusCounter(logEntry.getStatus());

            // 4. 연산별 카운터 업데이트
            metricsService.incrementOperationCounter(logEntry.getOperation());

            // 5. 테이블별 카운터 업데이트
            metricsService.incrementTableCounter(logEntry.getTableName());

            log.trace("Metrics updated for: logId={}", logEntry.getLogId());

        } catch (Exception e) {
            log.error("Failed to update metrics: logId={}", logEntry.getLogId(), e);
        }
    }

    /**
     * 검색 인덱스 업데이트
     */
    private void updateSearchIndex(WalLogEntry logEntry) {
        try {
            log.trace("Updating search index for: logId={}", logEntry.getLogId());

            // Elasticsearch 또는 다른 검색 엔진에 인덱싱
            // 실제 구현에서는 검색 서비스 연동

            log.trace("Search index updated for: logId={}", logEntry.getLogId());

        } catch (Exception e) {
            log.error("Failed to update search index: logId={}", logEntry.getLogId(), e);
        }
    }

    /**
     * 데이터 크기 계산
     */
    private int calculateDataSize(WalLogEntry logEntry) {
        int beforeSize = logEntry.getBeforeData() != null ? logEntry.getBeforeData().length() : 0;
        int afterSize = logEntry.getAfterData() != null ? logEntry.getAfterData().length() : 0;
        return beforeSize + afterSize;
    }

    /**
     * 아카이빙 재시도 스케줄링
     */
    private void scheduleRetryArchiving(List<WalLogEntry> logs) {
        try {
            log.warn("Scheduling retry archiving for {} logs", logs.size());

            // 5분 후 재시도
            CompletableFuture.delayedExecutor(5, java.util.concurrent.TimeUnit.MINUTES)
                    .execute(() -> {
                        log.info("Retrying archiving for {} logs", logs.size());
                        archiveLogs(logs);
                    });

        } catch (Exception e) {
            log.error("Failed to schedule retry archiving", e);
        }
    }

    /**
     * 정리 작업 (애플리케이션 종료 시)
     */
    public void cleanup() {
        try {
            log.info("Starting WAL async processor cleanup");

            // 진행 중인 작업 완료 대기
            // 실제 구현에서는 ExecutorService 종료 처리

            log.info("WAL async processor cleanup completed");

        } catch (Exception e) {
            log.error("Error during WAL async processor cleanup", e);
        }
    }
}


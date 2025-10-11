package com.example.payment.config;

import com.example.payment.domain.entity.WalLogEntry;
import com.example.payment.infrastructure.persistence.wal.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * WAL(Write-Ahead Logging) 설정
 *
 * 구성 요소:
 * - WalArchiveService: 오래된 로그 아카이빙 (NoOp 구현)
 * - WalBackupService: 로그 백업 (NoOp 구현)
 * - WalMetricsService: 메트릭 수집 (NoOp 구현)
 * - 스케줄러: WAL 정리 작업용 스레드 풀
 *
 * 💡 NoOp 구현 사용 이유:
 * - 개발 단계에서는 간단한 구현으로 시작
 * - 프로덕션에서는 실제 구현체로 교체 가능
 * - 설정 파일 내에서 모든 동작 확인 가능
 */
@Configuration
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class WalConfiguration implements SchedulingConfigurer {

    private final WalProperties walProperties;

    // ========================================
    // WAL 서비스 빈 등록
    // ========================================

    /**
     * WAL 아카이브 서비스 (NoOp 구현)
     *
     * 역할: 오래된 WAL 로그를 장기 보관소로 이동
     * 현재: 로그만 출력 (실제 아카이빙 없음)
     * 향후: S3, GCS 등 외부 스토리지 연동 가능
     */
    @Bean
    @ConditionalOnMissingBean(WalArchiveService.class)
    @ConditionalOnProperty(name = "wal.archive.auto-archive", havingValue = "true", matchIfMissing = true)
    public WalArchiveService walArchiveService() {
        log.info("Initializing WAL Archive Service (NoOp implementation)");

        return new WalArchiveService() {

            @Override
            public void archiveToStorage(List<WalLogEntry> logs) {
                if (logs == null || logs.isEmpty()) {
                    return;
                }

                log.debug("📦 Archiving {} WAL logs to storage (NoOp)", logs.size());

                // 실제 구현에서는 여기서 S3/GCS 업로드
                // Example:
                // s3Client.putObject(bucket, key, serializeLogs(logs));

                if (log.isTraceEnabled()) {
                    logs.forEach(entry ->
                            log.trace("  - logId={}, operation={}, status={}",
                                    entry.getLogId(), entry.getOperation(), entry.getStatus())
                    );
                }
            }

            @Override
            public void compressArchivedLogs(List<WalLogEntry> logs) {
                if (logs == null || logs.isEmpty()) {
                    return;
                }

                if (walProperties.getArchive().isCompressArchivedLogs()) {
                    log.debug("🗜️ Compressing {} archived WAL logs (NoOp)", logs.size());

                    // 실제 구현에서는 여기서 GZIP/LZ4 압축
                    // Example:
                    // byte[] compressed = compress(logs, CompressionType.GZIP);
                }
            }

            @Override
            public void validateArchivedLogs(List<WalLogEntry> logs) {
                if (logs == null || logs.isEmpty()) {
                    return;
                }

                log.debug("✅ Validating {} archived WAL logs (NoOp)", logs.size());

                // 실제 구현에서는 체크섬 검증
                // Example:
                // logs.forEach(log -> validateChecksum(log));
            }
        };
    }

    /**
     * WAL 백업 서비스 (NoOp 구현)
     *
     * 역할: 로그의 복제본 생성 및 무결성 검증
     * 현재: 로그만 출력 (실제 백업 없음)
     * 향후: 별도 백업 스토리지로 실시간 복제
     */
    @Bean
    @ConditionalOnMissingBean(WalBackupService.class)
    public WalBackupService walBackupService() {
        log.info("Initializing WAL Backup Service (NoOp implementation)");

        return new WalBackupService() {

            @Override
            public void createBackupCopy(WalLogEntry logEntry) {
                if (logEntry == null) {
                    return;
                }

                log.trace("💾 Creating backup copy for WAL log: logId={} (NoOp)",
                        logEntry.getLogId());

                // 실제 구현에서는 별도 백업 저장소로 복제
                // Example:
                // backupStorage.replicate(logEntry);
            }

            @Override
            public void verifyBackupIntegrity(WalLogEntry logEntry) {
                if (logEntry == null) {
                    return;
                }

                log.trace("🔍 Verifying backup integrity: logId={} (NoOp)",
                        logEntry.getLogId());

                // 실제 구현에서는 체크섬/해시 검증
                // Example:
                // String originalHash = calculateHash(logEntry);
                // String backupHash = backupStorage.getHash(logEntry.getLogId());
                // if (!originalHash.equals(backupHash)) {
                //     throw new BackupCorruptionException();
                // }
            }

            @Override
            public void cleanupOldBackups(LocalDateTime before) {
                log.debug("🧹 Cleaning up old backups before: {} (NoOp)", before);

                // 실제 구현에서는 오래된 백업 삭제
                // Example:
                // backupStorage.deleteOlderThan(before);
            }
        };
    }

    /**
     * WAL 메트릭 서비스 (NoOp 구현)
     *
     * 역할: WAL 작업 성능 메트릭 수집
     * 현재: 아무 동작 안 함 (성능 최적화)
     * 향후: Prometheus/Grafana 연동 가능
     */
    @Bean
    @ConditionalOnMissingBean(WalMetricsService.class)
    @ConditionalOnProperty(name = "wal.metrics.enabled", havingValue = "true", matchIfMissing = true)
    public WalMetricsService walMetricsService() {
        log.info("Initializing WAL Metrics Service (NoOp implementation)");

        return new WalMetricsService() {

            @Override
            public void recordProcessingTime(String operation, long timeMs) {
                // NoOp - 성능을 위해 아무 동작 안 함
                // 실제 구현:
                // meterRegistry.timer("wal.processing.time", "operation", operation)
                //     .record(timeMs, TimeUnit.MILLISECONDS);
            }

            @Override
            public void recordDataSize(String operation, int sizeBytes) {
                // NoOp
                // 실제 구현:
                // meterRegistry.counter("wal.data.size", "operation", operation)
                //     .increment(sizeBytes);
            }

            @Override
            public void incrementStatusCounter(String status) {
                // NoOp
                // 실제 구현:
                // meterRegistry.counter("wal.status", "status", status).increment();
            }

            @Override
            public void incrementOperationCounter(String operation) {
                // NoOp
                // 실제 구현:
                // meterRegistry.counter("wal.operation", "type", operation).increment();
            }

            @Override
            public void incrementTableCounter(String tableName) {
                // NoOp
                // 실제 구현:
                // meterRegistry.counter("wal.table", "name", tableName).increment();
            }

            @Override
            public void incrementCompressionCount(String operation) {
                // NoOp
            }

            @Override
            public void incrementCompressionFailure(String operation) {
                // NoOp
            }

            @Override
            public void incrementBackupCount(String operation) {
                // NoOp
            }

            @Override
            public void incrementBackupFailure(String operation) {
                // NoOp
            }

            @Override
            public void incrementAsyncProcessingFailure(String operation) {
                // NoOp
            }

            @Override
            public void updateArchivingMetrics(int archivedCount) {
                // NoOp
            }

            @Override
            public void incrementArchivingFailure(int failedCount) {
                // NoOp
            }
        };
    }

    // ========================================
    // 스케줄러 설정
    // ========================================

    /**
     * WAL 정리 작업용 스레드 풀 설정
     *
     * 특징:
     * - 데몬이 아닌 일반 스레드 (JVM 종료 시 완료 대기)
     * - 4개의 스레드로 병렬 처리
     * - 정리 작업, 아카이빙, 백업 검증 등에 사용
     */
    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.setScheduler(Executors.newScheduledThreadPool(4, r -> {
            Thread t = new Thread(r);
            t.setName("WAL-Scheduler-" + t.getId());
            t.setDaemon(false); // 중요: 데몬이 아니므로 작업 완료 대기
            t.setPriority(Thread.NORM_PRIORITY);
            return t;
        }));

        log.info("WAL Scheduler configured with 4 threads");
    }

    // ========================================
    // 설정 정보 로깅 (시작 시)
    // ========================================

    @Bean
    public WalConfigurationLogger walConfigurationLogger() {
        return new WalConfigurationLogger(walProperties);
    }

    /**
     * WAL 설정 정보 로거
     */
    static class WalConfigurationLogger {

        public WalConfigurationLogger(WalProperties walProperties) {
            logConfiguration(walProperties);
        }

        private void logConfiguration(WalProperties props) {
            log.info("========================================");
            log.info("WAL Configuration");
            log.info("========================================");
            log.info("Logging:");
            log.info("  - Sync Write: {}", props.getLogging().isSyncWrite());
            log.info("  - Batch Size: {}", props.getLogging().getBatchSize());
            log.info("  - Flush Interval: {}ms", props.getLogging().getFlushIntervalMs());
            log.info("  - Compression Threshold: {} bytes", props.getLogging().getCompressionThreshold());
            log.info("  - Retention: {} days", props.getLogging().getRetentionDays());

            log.info("Recovery:");
            log.info("  - Auto Recovery: {}", props.getRecovery().isAutoRecoveryOnStartup());
            log.info("  - Batch Size: {}", props.getRecovery().getRecoveryBatchSize());
            log.info("  - Max Time: {} minutes", props.getRecovery().getMaxRecoveryTimeMinutes());

            log.info("Archive:");
            log.info("  - Auto Archive: {}", props.getArchive().isAutoArchive());
            log.info("  - Schedule: {}", props.getArchive().getArchiveSchedule());
            log.info("  - Archive After: {} days", props.getArchive().getArchiveAfterDays());
            log.info("  - Compress: {}", props.getArchive().isCompressArchivedLogs());

            log.info("Metrics:");
            log.info("  - Enabled: {}", props.getMetrics().isEnabled());
            log.info("  - Update Interval: {} seconds", props.getMetrics().getUpdateIntervalSeconds());
            log.info("  - Detailed: {}", props.getMetrics().isDetailedMetrics());

            log.info("Alerts:");
            log.info("  - Enabled: {}", props.getAlerts().isEnabled());
            log.info("  - Failed Log Threshold: {}", props.getAlerts().getFailedLogThreshold());
            log.info("  - Delay Threshold: {} minutes", props.getAlerts().getProcessingDelayThresholdMinutes());
            log.info("  - Disk Usage Threshold: {}%", props.getAlerts().getDiskUsageThresholdPercent());
            log.info("========================================");
        }
    }
}
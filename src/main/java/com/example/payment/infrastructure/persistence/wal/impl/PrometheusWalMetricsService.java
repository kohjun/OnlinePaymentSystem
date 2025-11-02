package com.example.payment.infrastructure.persistence.wal.impl;

import com.example.payment.infrastructure.persistence.wal.WalMetricsService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WAL 메트릭 서비스 - Prometheus 통합
 *
 * 🎯 메트릭 카테고리:
 * 1. 작업 메트릭 (Operation Metrics)
 *    - 작업별 성공/실패 카운트
 *    - 작업 소요 시간 (Latency)
 *
 * 2. 로그 메트릭 (Log Metrics)
 *    - 상태별 로그 개수
 *    - 로그 크기 분포
 *
 * 3. 복구 메트릭 (Recovery Metrics)
 *    - 복구 성공/실패 카운트
 *    - Phase별 복구 통계
 *
 * 4. 성능 메트릭 (Performance Metrics)
 *    - 압축률
 *    - 백업 성공률
 *    - 비동기 처리 실패율
 */
@Service
@Slf4j
public class PrometheusWalMetricsService implements WalMetricsService {

    private final MeterRegistry meterRegistry;

    // 상태별 카운터
    private final AtomicLong pendingCount = new AtomicLong(0);
    private final AtomicLong inProgressCount = new AtomicLong(0);
    private final AtomicLong committedCount = new AtomicLong(0);
    private final AtomicLong failedCount = new AtomicLong(0);

    // 동적 카운터 캐시
    private final ConcurrentHashMap<String, Counter> operationCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> failureCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> latencyTimers = new ConcurrentHashMap<>();

    public PrometheusWalMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        registerGauges();
    }

    /**
     * Gauge 메트릭 등록 (상태별 로그 개수)
     */
    private void registerGauges() {
        Gauge.builder("wal.logs.pending", pendingCount, AtomicLong::get)
                .description("Number of pending WAL logs")
                .register(meterRegistry);

        Gauge.builder("wal.logs.in_progress", inProgressCount, AtomicLong::get)
                .description("Number of in-progress WAL logs")
                .register(meterRegistry);

        Gauge.builder("wal.logs.committed", committedCount, AtomicLong::get)
                .description("Number of committed WAL logs")
                .register(meterRegistry);

        Gauge.builder("wal.logs.failed", failedCount, AtomicLong::get)
                .description("Number of failed WAL logs")
                .register(meterRegistry);

        log.info("✅ WAL Prometheus metrics registered");
    }

    @Override
    public void incrementOperationCount(String operation) {
        operationCounters.computeIfAbsent(operation, op ->
                Counter.builder("wal.operation.count")
                        .tag("operation", op)
                        .description("Number of WAL operations")
                        .register(meterRegistry)
        ).increment();
    }

    @Override
    public void incrementFailureCount(String operation) {
        failureCounters.computeIfAbsent(operation, op ->
                Counter.builder("wal.operation.failure")
                        .tag("operation", op)
                        .description("Number of WAL operation failures")
                        .register(meterRegistry)
        ).increment();
    }

    @Override
    public void recordWriteLatency(String operation, long latencyMs) {
        latencyTimers.computeIfAbsent(operation, op ->
                Timer.builder("wal.write.latency")
                        .tag("operation", op)
                        .description("WAL write latency")
                        .register(meterRegistry)
        ).record(latencyMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void updateLogSize(String operation, long sizeBytes) {
        meterRegistry.gauge("wal.log.size",
                java.util.List.of(io.micrometer.core.instrument.Tag.of("operation", operation)),
                sizeBytes);
    }

    @Override
    public void incrementTableCounter(String tableName) {
        Counter.builder("wal.table.operations")
                .tag("table", tableName)
                .description("Number of operations per table")
                .register(meterRegistry)
                .increment();
    }

    @Override
    public void incrementCompressionCount(String operation) {
        Counter.builder("wal.compression.count")
                .tag("operation", operation)
                .description("Number of log compressions")
                .register(meterRegistry)
                .increment();
    }

    @Override
    public void incrementCompressionFailure(String operation) {
        Counter.builder("wal.compression.failure")
                .tag("operation", operation)
                .description("Number of compression failures")
                .register(meterRegistry)
                .increment();
    }

    @Override
    public void incrementBackupCount(String operation) {
        Counter.builder("wal.backup.count")
                .tag("operation", operation)
                .description("Number of backup operations")
                .register(meterRegistry)
                .increment();
    }

    @Override
    public void incrementBackupFailure(String operation) {
        Counter.builder("wal.backup.failure")
                .tag("operation", operation)
                .description("Number of backup failures")
                .register(meterRegistry)
                .increment();
    }

    @Override
    public void incrementAsyncProcessingFailure(String operation) {
        Counter.builder("wal.async.failure")
                .tag("operation", operation)
                .description("Number of async processing failures")
                .register(meterRegistry)
                .increment();
    }

    @Override
    public void updateArchivingMetrics(int archivedCount) {
        Counter.builder("wal.archive.count")
                .description("Number of archived logs")
                .register(meterRegistry)
                .increment(archivedCount);
    }

    @Override
    public void incrementArchivingFailure(int failedCount) {
        Counter.builder("wal.archive.failure")
                .description("Number of archiving failures")
                .register(meterRegistry)
                .increment(failedCount);
    }

    @Override
    public void incrementRecoveryCount(String phase) {
        Counter.builder("wal.recovery.count")
                .tag("phase", phase)
                .description("Number of recovered transactions")
                .register(meterRegistry)
                .increment();
    }

    @Override
    public void incrementRecoveryFailure(String phase) {
        Counter.builder("wal.recovery.failure")
                .tag("phase", phase)
                .description("Number of recovery failures")
                .register(meterRegistry)
                .increment();
    }

    @Override
    public void updateWalStatistics(long pending, long inProgress, long committed, long failed) {
        pendingCount.set(pending);
        inProgressCount.set(inProgress);
        committedCount.set(committed);
        failedCount.set(failed);

        log.debug("📊 WAL metrics updated: pending={}, inProgress={}, committed={}, failed={}",
                pending, inProgress, committed, failed);
    }
}
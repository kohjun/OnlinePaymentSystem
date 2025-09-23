package com.example.payment.infrastructure.persistence.wal.impl;

import com.example.payment.infrastructure.persistence.wal.WalMetricsService;
import com.example.payment.config.WalProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.lang.Nullable;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@RequiredArgsConstructor
public class DefaultWalMetricsService implements WalMetricsService {

    private final WalProperties.Metrics metricsConfig;
    @Nullable
    private final Object meterRegistry; // MeterRegistry가 없을 수도 있음

    // MeterRegistry가 없는 경우를 대비한 간단한 메트릭 저장소
    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> timers = new ConcurrentHashMap<>();

    @Override
    public void recordProcessingTime(String operation, long timeMs) {
        if (!metricsConfig.isEnabled()) {
            return;
        }

        try {
            if (meterRegistry != null) {
                // Micrometer 사용 (실제 구현에서는 MeterRegistry 타입 체크 후 사용)
                log.debug("Recording processing time: operation={}, timeMs={}", operation, timeMs);
            } else {
                // 간단한 로컬 메트릭
                String key = "processing_time_" + operation;
                timers.computeIfAbsent(key, k -> new AtomicLong(0)).addAndGet(timeMs);
                log.trace("Local metric - processing time recorded: operation={}, timeMs={}", operation, timeMs);
            }
        } catch (Exception e) {
            log.warn("Failed to record processing time metric", e);
        }
    }

    @Override
    public void recordDataSize(String operation, int sizeBytes) {
        if (!metricsConfig.isEnabled()) {
            return;
        }

        try {
            if (meterRegistry != null) {
                log.debug("Recording data size: operation={}, sizeBytes={}", operation, sizeBytes);
            } else {
                String key = "data_size_" + operation;
                counters.computeIfAbsent(key, k -> new AtomicLong(0)).addAndGet(sizeBytes);
                log.trace("Local metric - data size recorded: operation={}, sizeBytes={}", operation, sizeBytes);
            }
        } catch (Exception e) {
            log.warn("Failed to record data size metric", e);
        }
    }

    @Override
    public void incrementStatusCounter(String status) {
        if (!metricsConfig.isEnabled()) {
            return;
        }

        try {
            if (meterRegistry != null) {
                log.debug("Incrementing status counter: status={}", status);
            } else {
                String key = "status_count_" + status;
                counters.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
                log.trace("Local metric - status counter incremented: status={}", status);
            }
        } catch (Exception e) {
            log.warn("Failed to increment status counter", e);
        }
    }

    @Override
    public void incrementOperationCounter(String operation) {
        if (!metricsConfig.isEnabled()) {
            return;
        }

        try {
            if (meterRegistry != null) {
                log.debug("Incrementing operation counter: operation={}", operation);
            } else {
                String key = "operation_count_" + operation;
                counters.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
                log.trace("Local metric - operation counter incremented: operation={}", operation);
            }
        } catch (Exception e) {
            log.warn("Failed to increment operation counter", e);
        }
    }

    @Override
    public void incrementTableCounter(String tableName) {
        if (!metricsConfig.isEnabled()) {
            return;
        }

        try {
            if (meterRegistry != null) {
                log.debug("Incrementing table counter: table={}", tableName);
            } else {
                String key = "table_count_" + tableName;
                counters.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
                log.trace("Local metric - table counter incremented: table={}", tableName);
            }
        } catch (Exception e) {
            log.warn("Failed to increment table counter", e);
        }
    }

    @Override
    public void incrementCompressionCount(String operation) {
        if (!metricsConfig.isEnabled()) {
            return;
        }

        try {
            String key = "compression_count_" + operation;
            counters.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
            log.trace("Compression count incremented: operation={}", operation);
        } catch (Exception e) {
            log.warn("Failed to increment compression count", e);
        }
    }

    @Override
    public void incrementCompressionFailure(String operation) {
        if (!metricsConfig.isEnabled()) {
            return;
        }

        try {
            String key = "compression_failure_" + operation;
            counters.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
            log.trace("Compression failure incremented: operation={}", operation);
        } catch (Exception e) {
            log.warn("Failed to increment compression failure", e);
        }
    }

    @Override
    public void incrementBackupCount(String operation) {
        if (!metricsConfig.isEnabled()) {
            return;
        }

        try {
            String key = "backup_count_" + operation;
            counters.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
            log.trace("Backup count incremented: operation={}", operation);
        } catch (Exception e) {
            log.warn("Failed to increment backup count", e);
        }
    }

    @Override
    public void incrementBackupFailure(String operation) {
        if (!metricsConfig.isEnabled()) {
            return;
        }

        try {
            String key = "backup_failure_" + operation;
            counters.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
            log.trace("Backup failure incremented: operation={}", operation);
        } catch (Exception e) {
            log.warn("Failed to increment backup failure", e);
        }
    }

    @Override
    public void incrementAsyncProcessingFailure(String operation) {
        if (!metricsConfig.isEnabled()) {
            return;
        }

        try {
            String key = "async_failure_" + operation;
            counters.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
            log.trace("Async processing failure incremented: operation={}", operation);
        } catch (Exception e) {
            log.warn("Failed to increment async processing failure", e);
        }
    }

    @Override
    public void updateArchivingMetrics(int archivedCount) {
        if (!metricsConfig.isEnabled()) {
            return;
        }

        try {
            String key = "archived_logs_total";
            counters.computeIfAbsent(key, k -> new AtomicLong(0)).addAndGet(archivedCount);
            log.debug("Archiving metrics updated: archivedCount={}", archivedCount);
        } catch (Exception e) {
            log.warn("Failed to update archiving metrics", e);
        }
    }

    @Override
    public void incrementArchivingFailure(int failedCount) {
        if (!metricsConfig.isEnabled()) {
            return;
        }

        try {
            String key = "archiving_failures_total";
            counters.computeIfAbsent(key, k -> new AtomicLong(0)).addAndGet(failedCount);
            log.debug("Archiving failure incremented: failedCount={}", failedCount);
        } catch (Exception e) {
            log.warn("Failed to increment archiving failure", e);
        }
    }

    /**
     * 로컬 메트릭 조회 (디버깅용)
     */
    public java.util.Map<String, Long> getLocalMetrics() {
        java.util.Map<String, Long> metrics = new java.util.HashMap<>();

        counters.forEach((key, value) -> metrics.put(key, value.get()));
        timers.forEach((key, value) -> metrics.put(key, value.get()));

        return metrics;
    }

    /**
     * 메트릭 초기화 (테스트용)
     */
    public void resetMetrics() {
        counters.clear();
        timers.clear();
        log.debug("WAL metrics reset");
    }

    /**
     * 메트릭 사용 가능 여부 확인
     */
    public boolean isMetricsEnabled() {
        return metricsConfig.isEnabled();
    }
}
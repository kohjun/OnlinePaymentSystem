package com.example.payment.infrastructure.persistence.wal.impl;

import com.example.payment.infrastructure.persistence.wal.WalMetricsService;
import com.example.payment.config.WalProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;

@Slf4j
@RequiredArgsConstructor
public class DefaultWalMetricsService implements WalMetricsService {

    private final WalProperties.Metrics metricsConfig;
    private final MeterRegistry meterRegistry;

    @Override
    public void recordProcessingTime(String operation, long timeMs) {
        if (metricsConfig.isEnabled()) {
            Timer.Sample.start(meterRegistry)
                    .stop(Timer.builder("wal.processing.time")
                            .tag("operation", operation)
                            .register(meterRegistry));
        }
    }

    @Override
    public void recordDataSize(String operation, int sizeBytes) {
        if (metricsConfig.isEnabled()) {
            meterRegistry.summary("wal.data.size", "operation", operation)
                    .record(sizeBytes);
        }
    }

    @Override
    public void incrementStatusCounter(String status) {
        if (metricsConfig.isEnabled()) {
            Counter.builder("wal.logs.count")
                    .tag("status", status)
                    .register(meterRegistry)
                    .increment();
        }
    }

    @Override
    public void incrementOperationCounter(String operation) {
        if (metricsConfig.isEnabled()) {
            Counter.builder("wal.operations.count")
                    .tag("operation", operation)
                    .register(meterRegistry)
                    .increment();
        }
    }

    @Override
    public void incrementTableCounter(String tableName) {
        if (metricsConfig.isEnabled()) {
            Counter.builder("wal.tables.count")
                    .tag("table", tableName)
                    .register(meterRegistry)
                    .increment();
        }
    }

    @Override
    public void incrementCompressionCount(String operation) {
        if (metricsConfig.isEnabled()) {
            Counter.builder("wal.compression.count")
                    .tag("operation", operation)
                    .register(meterRegistry)
                    .increment();
        }
    }

    @Override
    public void incrementCompressionFailure(String operation) {
        if (metricsConfig.isEnabled()) {
            Counter.builder("wal.compression.failures")
                    .tag("operation", operation)
                    .register(meterRegistry)
                    .increment();
        }
    }

    @Override
    public void incrementBackupCount(String operation) {
        if (metricsConfig.isEnabled()) {
            Counter.builder("wal.backup.count")
                    .tag("operation", operation)
                    .register(meterRegistry)
                    .increment();
        }
    }

    @Override
    public void incrementBackupFailure(String operation) {
        if (metricsConfig.isEnabled()) {
            Counter.builder("wal.backup.failures")
                    .tag("operation", operation)
                    .register(meterRegistry)
                    .increment();
        }
    }

    @Override
    public void incrementAsyncProcessingFailure(String operation) {
        if (metricsConfig.isEnabled()) {
            Counter.builder("wal.async.failures")
                    .tag("operation", operation)
                    .register(meterRegistry)
                    .increment();
        }
    }

    @Override
    public void updateArchivingMetrics(int archivedCount) {
        if (metricsConfig.isEnabled()) {
            meterRegistry.gauge("wal.archived.logs", archivedCount);
        }
    }

    @Override
    public void incrementArchivingFailure(int failedCount) {
        if (metricsConfig.isEnabled()) {
            Counter.builder("wal.archiving.failures")
                    .register(meterRegistry)
                    .increment(failedCount);
        }
    }
}
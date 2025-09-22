package com.example.payment.infrastructure.persistence.wal;

public interface WalMetricsService {
    void recordProcessingTime(String operation, long timeMs);
    void recordDataSize(String operation, int sizeBytes);
    void incrementStatusCounter(String status);
    void incrementOperationCounter(String operation);
    void incrementTableCounter(String tableName);
    void incrementCompressionCount(String operation);
    void incrementCompressionFailure(String operation);
    void incrementBackupCount(String operation);
    void incrementBackupFailure(String operation);
    void incrementAsyncProcessingFailure(String operation);
    void updateArchivingMetrics(int archivedCount);
    void incrementArchivingFailure(int failedCount);
}
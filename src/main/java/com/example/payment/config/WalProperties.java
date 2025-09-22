package com.example.payment.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "wal")
public class WalProperties {

    private Logging logging = new Logging();
    private Recovery recovery = new Recovery();
    private Archive archive = new Archive();
    private Metrics metrics = new Metrics();
    private Alerts alerts = new Alerts();

    @Data
    public static class Logging {
        private boolean syncWrite = true;
        private int batchSize = 100;
        private long flushIntervalMs = 50;
        private int compressionThreshold = 1024;
        private int retentionDays = 30;
    }

    @Data
    public static class Recovery {
        private boolean autoRecoveryOnStartup = true;
        private int recoveryBatchSize = 50;
        private int maxRecoveryTimeMinutes = 30;
    }

    @Data
    public static class Archive {
        private boolean autoArchive = true;
        private String archiveSchedule = "0 0 2 * * ?";
        private int archiveAfterDays = 7;
        private boolean compressArchivedLogs = true;
    }

    @Data
    public static class Metrics {
        private boolean enabled = true;
        private int updateIntervalSeconds = 60;
        private boolean detailedMetrics = true;
    }

    @Data
    public static class Alerts {
        private boolean enabled = true;
        private int failedLogThreshold = 10;
        private int processingDelayThresholdMinutes = 5;
        private int diskUsageThresholdPercent = 80;
    }
}
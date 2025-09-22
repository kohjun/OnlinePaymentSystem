package com.example.payment.config;

import com.example.payment.infrastructure.persistence.wal.WalArchiveService;
import com.example.payment.infrastructure.persistence.wal.WalBackupService;
import com.example.payment.infrastructure.persistence.wal.WalMetricsService;
import com.example.payment.infrastructure.persistence.wal.impl.DefaultWalArchiveService;
import com.example.payment.infrastructure.persistence.wal.impl.DefaultWalBackupService;
import com.example.payment.infrastructure.persistence.wal.impl.DefaultWalMetricsService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.util.concurrent.Executors;

@Configuration
@RequiredArgsConstructor
public class WalConfiguration implements SchedulingConfigurer {

    private final WalProperties walProperties;

    /**
     * WAL 아카이브 서비스 구현체
     */
    @Bean
    @ConditionalOnProperty(name = "wal.archive.auto-archive", havingValue = "true", matchIfMissing = true)
    public WalArchiveService walArchiveService() {
        return new DefaultWalArchiveService(walProperties.getArchive());
    }

    /**
     * WAL 메트릭 서비스 구현체
     */
    @Bean
    @ConditionalOnProperty(name = "wal.metrics.enabled", havingValue = "true", matchIfMissing = true)
    public WalMetricsService walMetricsService() {
        return new DefaultWalMetricsService(walProperties.getMetrics());
    }

    /**
     * WAL 백업 서비스 구현체
     */
    @Bean
    public WalBackupService walBackupService() {
        return new DefaultWalBackupService();
    }

    /**
     * WAL 모니터링 서비스
     */
    @Bean
    @ConditionalOnProperty(name = "wal.alerts.enabled", havingValue = "true", matchIfMissing = true)
    public WalMonitoringService walMonitoringService() {
        return new DefaultWalMonitoringService(walProperties.getAlerts());
    }

    /**
     * 스케줄링 태스크 전용 스레드 풀 설정
     */
    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.setScheduler(Executors.newScheduledThreadPool(4, r -> {
            Thread t = new Thread(r);
            t.setName("WAL-Scheduler-" + t.getId());
            t.setDaemon(false);
            return t;
        }));
    }
}

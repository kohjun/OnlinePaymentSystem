package com.example.payment.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
@EnableScheduling
@Slf4j
public class WalExecutorConfig {

    @Value("${thread-pool.wal.core-pool-size:4}")
    private int corePoolSize;

    @Value("${thread-pool.wal.max-pool-size:8}")
    private int maxPoolSize;

    @Value("${thread-pool.wal.queue-capacity:1000}")
    private int queueCapacity;

    @Value("${thread-pool.wal.thread-name-prefix:WAL-}")
    private String threadNamePrefix;

    @Value("${thread-pool.wal.keep-alive-seconds:60}")
    private int keepAliveSeconds;

    /**
     * WAL 전용 스레드 풀 Executor
     */
    @Bean("walExecutor")
    public Executor walExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // 기본 설정
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setKeepAliveSeconds(keepAliveSeconds);

        // 스레드 풀 종료 시 진행 중인 작업 완료 대기
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);

        // 거부된 작업 처리 정책 (CallerRuns: 호출 스레드에서 직접 실행)
        executor.setRejectedExecutionHandler(new WalRejectedExecutionHandler());

        // 스레드 팩토리 설정
        executor.setThreadFactory(r -> {
            Thread t = new Thread(r);
            t.setName(threadNamePrefix + t.getId());
            t.setDaemon(false); // 데몬 스레드가 아니므로 JVM 종료 시 완료 대기
            return t;
        });

        executor.initialize();

        log.info("WAL Executor initialized: corePoolSize={}, maxPoolSize={}, queueCapacity={}",
                corePoolSize, maxPoolSize, queueCapacity);

        return executor;
    }

    /**
     * WAL 전용 거부 처리 핸들러
     */
    private static class WalRejectedExecutionHandler implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            log.warn("WAL task rejected, executing in caller thread: activeCount={}, queueSize={}",
                    executor.getActiveCount(), executor.getQueue().size());

            // 중요한 WAL 작업이므로 호출 스레드에서 직접 실행
            if (!executor.isShutdown()) {
                r.run();
            }
        }
    }
}
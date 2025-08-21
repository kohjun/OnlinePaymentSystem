/**
 * Simplified Write Buffer Service
 * Write-Through with Buffer 캐싱 전략 구현 (단순화된 버전)
 * - 즉시 캐시 업데이트 후 DB 쓰기는 비동기 버퍼링
 * - 단일 메인 버퍼 + 재시도 버퍼로 단순화
 */
package com.example.payment.infrastructure.buffer;

import com.example.payment.infrastructure.monitoring.MonitoringService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
@RequiredArgsConstructor
public class WriteBufferService {

    private final WriteCommandProcessor commandProcessor;
    private final MonitoringService monitoringService;

    // 메인 버퍼 - 모든 쓰기 명령 처리
    private final BlockingQueue<WriteCommand> primaryBuffer = new LinkedBlockingQueue<>(10000);

    // 재시도 버퍼 - 실패한 명령들
    private final BlockingQueue<WriteCommand> retryBuffer = new LinkedBlockingQueue<>(1000);

    // 메트릭 수집용 카운터
    private final AtomicLong totalEnqueued = new AtomicLong(0);
    private final AtomicLong totalProcessed = new AtomicLong(0);
    private final AtomicLong totalFailed = new AtomicLong(0);
    private final AtomicLong totalRejected = new AtomicLong(0);

    /**
     * 명령을 버퍼에 큐잉 (논블로킹)
     */
    @Async("writeBufferExecutor")
    public void enqueue(WriteCommand command) {
        long startTime = System.nanoTime();

        try {
            boolean offered = primaryBuffer.offer(command);

            if (offered) {
                totalEnqueued.incrementAndGet();
                log.debug("Command enqueued: type={}, id={}",
                        command.getType(), command.getCommandId());
            } else {
                log.warn("Primary buffer overflow! Processing command synchronously: type={}, id={}",
                        command.getType(), command.getCommandId());

                // 버퍼 오버플로우 시 즉시 동기 처리 (데이터 무손실)
                boolean processed = commandProcessor.processCommand(command);
                if (processed) {
                    totalProcessed.incrementAndGet();
                } else {
                    totalFailed.incrementAndGet();
                }
                totalRejected.incrementAndGet();
            }

        } catch (Exception e) {
            log.error("Error enqueuing command: type={}, id={}",
                    command.getType(), command.getCommandId(), e);
            totalFailed.incrementAndGet();
        } finally {
            // 모니터링 메트릭 기록
            long duration = System.nanoTime() - startTime;
            monitoringService.recordCacheOperation("write-buffer",
                    MonitoringService.CacheOperation.SET, duration);
        }
    }

    /**
     * 메인 버퍼 처리 (50ms마다)
     */
    @Scheduled(fixedDelay = 50)
    public void flushPrimaryBuffer() {
        List<WriteCommand> batch = drainBuffer(primaryBuffer, 100);
        if (!batch.isEmpty()) {
            processBatch(batch, "PRIMARY");
        }
    }

    /**
     * 재시도 버퍼 처리 (500ms마다 - 낮은 빈도)
     */
    @Scheduled(fixedDelay = 500)
    public void flushRetryBuffer() {
        List<WriteCommand> batch = drainBuffer(retryBuffer, 50);
        if (!batch.isEmpty()) {
            processBatch(batch, "RETRY");
        }
    }

    /**
     * 버퍼에서 배치 추출
     */
    private List<WriteCommand> drainBuffer(BlockingQueue<WriteCommand> buffer, int maxSize) {
        List<WriteCommand> batch = new ArrayList<>();
        buffer.drainTo(batch, maxSize);
        return batch;
    }

    /**
     * 배치 처리
     */
    private void processBatch(List<WriteCommand> batch, String bufferType) {
        if (batch.isEmpty()) return;

        long batchStartTime = System.nanoTime();
        log.debug("Processing {} batch: {} commands", bufferType, batch.size());

        List<WriteCommand> failedCommands = new ArrayList<>();
        int successCount = 0;

        for (WriteCommand command : batch) {
            try {
                long commandStartTime = System.nanoTime();
                boolean success = commandProcessor.processCommand(command);

                if (success) {
                    successCount++;
                    totalProcessed.incrementAndGet();
                    log.trace("Command processed successfully: type={}, id={}",
                            command.getType(), command.getCommandId());
                } else {
                    handleFailedCommand(command, failedCommands);
                }

                // 개별 명령 처리 시간 모니터링
                long commandDuration = System.nanoTime() - commandStartTime;
                monitoringService.recordCacheOperation("command-" + command.getType(),
                        MonitoringService.CacheOperation.SET, commandDuration);

            } catch (Exception e) {
                log.error("Error processing command: type={}, id={}",
                        command.getType(), command.getCommandId(), e);
                handleFailedCommand(command, failedCommands);
            }
        }

        // 실패한 명령들을 재시도 버퍼로 이동
        if (!failedCommands.isEmpty()) {
            requeueFailedCommands(failedCommands);
        }

        // 배치 처리 완료 로그 및 메트릭
        long batchDuration = System.nanoTime() - batchStartTime;
        log.debug("Batch processing completed: type={}, total={}, success={}, failed={}, duration={}ms",
                bufferType, batch.size(), successCount, failedCommands.size(),
                batchDuration / 1_000_000);

        // 배치 처리 메트릭 기록
        monitoringService.recordCacheOperation("batch-" + bufferType.toLowerCase(),
                MonitoringService.CacheOperation.SET, batchDuration);
    }

    /**
     * 실패한 명령 처리 로직
     */
    private void handleFailedCommand(WriteCommand command, List<WriteCommand> failedCommands) {
        command.incrementRetryCount();

        if (command.canRetry()) {
            failedCommands.add(command);
            log.warn("Command failed, will retry: type={}, id={}, retryCount={}",
                    command.getType(), command.getCommandId(), command.getRetryCount());
        } else {
            log.error("Command permanently failed: type={}, id={}, retryCount={}",
                    command.getType(), command.getCommandId(), command.getRetryCount());
            totalFailed.incrementAndGet();

            // 영구 실패 명령 처리
            handlePermanentFailure(command);
        }
    }

    /**
     * 실패한 명령들을 재시도 버퍼로 재큐잉
     */
    private void requeueFailedCommands(List<WriteCommand> failedCommands) {
        for (WriteCommand failed : failedCommands) {
            boolean requeued = retryBuffer.offer(failed);
            if (!requeued) {
                log.error("Retry buffer overflow! Command lost: type={}, id={}, retryCount={}",
                        failed.getType(), failed.getCommandId(), failed.getRetryCount());
                totalFailed.incrementAndGet();

                // 영구 실패 처리
                handlePermanentFailure(failed);
            } else {
                log.debug("Command requeued for retry: type={}, id={}",
                        failed.getType(), failed.getCommandId());
            }
        }
    }

    /**
     * 영구 실패 명령 처리
     */
    private void handlePermanentFailure(WriteCommand command) {
        try {
            // DLQ(Dead Letter Queue)로 전송
            log.error("PERMANENT FAILURE - Manual intervention required: type={}, id={}, payload={}",
                    command.getType(), command.getCommandId(), command.getPayload());

            // 메트릭 기록
            monitoringService.recordCacheOperation("permanent-failure",
                    MonitoringService.CacheOperation.ERROR, 0);

        } catch (Exception e) {
            log.error("Error handling permanent failure for command: {}", command.getCommandId(), e);
        }
    }

    /**
     * 오래된 명령 정리 (5분마다)
     */
    @Scheduled(fixedRate = 300000)
    public void cleanupOldCommands() {
        LocalDateTime cutoffTime = LocalDateTime.now().minusHours(1);

        int cleanedPrimary = cleanupBufferOldCommands(primaryBuffer, cutoffTime);
        int cleanedRetry = cleanupBufferOldCommands(retryBuffer, cutoffTime);

        if (cleanedPrimary + cleanedRetry > 0) {
            log.info("Cleaned up old commands: primary={}, retry={}", cleanedPrimary, cleanedRetry);
        }
    }

    /**
     * 특정 버퍼에서 오래된 명령 정리
     */
    private int cleanupBufferOldCommands(BlockingQueue<WriteCommand> buffer, LocalDateTime cutoffTime) {
        List<WriteCommand> toRemove = new ArrayList<>();
        List<WriteCommand> toKeep = new ArrayList<>();

        buffer.drainTo(toKeep);

        for (WriteCommand command : toKeep) {
            if (command.getCreatedAt().isBefore(cutoffTime)) {
                toRemove.add(command);
                log.warn("Removing old command: type={}, id={}, age={}",
                        command.getType(), command.getCommandId(),
                        java.time.Duration.between(command.getCreatedAt(), LocalDateTime.now()));
            } else {
                buffer.offer(command);
            }
        }

        return toRemove.size();
    }

    /**
     * 버퍼 상태 조회 (모니터링 및 운영용)
     */
    public BufferStatus getBufferStatus() {
        return BufferStatus.builder()
                .primaryBufferSize(primaryBuffer.size())
                .retryBufferSize(retryBuffer.size())
                .totalEnqueued(totalEnqueued.get())
                .totalProcessed(totalProcessed.get())
                .totalFailed(totalFailed.get())
                .totalRejected(totalRejected.get())
                .build();
    }

    /**
     * 버퍼 플러시 (긴급 상황 시 수동 호출)
     */
    public void forceFlushAllBuffers() {
        log.info("Force flushing all buffers...");

        flushPrimaryBuffer();
        flushRetryBuffer();

        log.info("Force flush completed. Remaining: primary={}, retry={}",
                primaryBuffer.size(), retryBuffer.size());
    }

    /**
     * 특정 타입의 명령만 플러시
     */
    public void flushCommandType(String commandType) {
        log.info("Flushing commands of type: {}", commandType);

        flushSpecificTypeFromBuffer(primaryBuffer, commandType, "PRIMARY");
        flushSpecificTypeFromBuffer(retryBuffer, commandType, "RETRY");
    }

    /**
     * 특정 버퍼에서 특정 타입 명령만 처리
     */
    private void flushSpecificTypeFromBuffer(BlockingQueue<WriteCommand> buffer,
                                             String targetType, String bufferName) {
        List<WriteCommand> allCommands = new ArrayList<>();
        List<WriteCommand> targetCommands = new ArrayList<>();

        buffer.drainTo(allCommands);

        for (WriteCommand command : allCommands) {
            if (targetType.equals(command.getType())) {
                targetCommands.add(command);
            } else {
                buffer.offer(command);
            }
        }

        if (!targetCommands.isEmpty()) {
            log.info("Processing {} commands of type {} from {} buffer",
                    targetCommands.size(), targetType, bufferName);
            processBatch(targetCommands, bufferName + "-" + targetType);
        }
    }
}
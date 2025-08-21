/**
 * Write Buffer 서비스
 */
package com.example.payment.application.service;

import com.example.payment.infrastructure.buffer.WriteCommand;
import com.example.payment.infrastructure.buffer.WriteCommandProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Slf4j
@RequiredArgsConstructor
public class WriteBufferService {

    private final WriteCommandProcessor commandProcessor;

    // 메인 버퍼 (높은 우선순위)
    private final BlockingQueue<WriteCommand> primaryBuffer = new LinkedBlockingQueue<>(10000);

    // 재시도 버퍼 (낮은 우선순위)
    private final BlockingQueue<WriteCommand> retryBuffer = new LinkedBlockingQueue<>(1000);

    // 메트릭 수집
    private final AtomicLong totalEnqueued = new AtomicLong(0);
    private final AtomicLong totalProcessed = new AtomicLong(0);
    private final AtomicLong totalFailed = new AtomicLong(0);

    /**
     * 명령을 버퍼에 큐잉 (논블로킹)
     */
    @Async("writeBufferExecutor")
    public void enqueue(WriteCommand command) {
        boolean offered = primaryBuffer.offer(command);

        if (offered) {
            totalEnqueued.incrementAndGet();
            log.debug("Command enqueued: type={}, id={}", command.getType(), command.getCommandId());
        } else {
            log.warn("Buffer overflow! Command rejected: type={}, id={}",
                    command.getType(), command.getCommandId());
            // 긴급한 경우 동기 처리
            commandProcessor.processCommand(command);
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
     * 재시도 버퍼 처리 (500ms마다 - 낮은 우선순위)
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
        log.debug("Processing {} batch: {} commands", bufferType, batch.size());

        List<WriteCommand> failedCommands = new ArrayList<>();

        for (WriteCommand command : batch) {
            try {
                boolean success = commandProcessor.processCommand(command);

            } else {
                handleFailedCommand(command, failedCommands);
            }

        } catch (Exception e) {
            log.error("Error processing command: type={}, id={}",
                    command.getType(), command.getCommandId(), e);
            handleFailedCommand(command, failedCommands);
        }
    }

    // 실패한 명령들을 재시도 버퍼로 이동
        if (!failedCommands.isEmpty()) {
        for (WriteCommand failed : failedCommands) {
            if (!retryBuffer.offer(failed)) {
                log.error("Retry buffer overflow! Command lost: type={}, id={}",
                        failed.getType(), failed.getCommandId());
                totalFailed.incrementAndGet();
            }
        }
    }
}

/**
 * 실패한 명령 처리
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

        // DLQ(Dead Letter Queue)로 전송하거나 알림 발송
        handlePermanentFailure(command);
    }
}

/**
 * 영구 실패 명령 처리
 */
private void handlePermanentFailure(WriteCommand command) {
    // TODO: DLQ 전송, 관리자 알림, 메트릭 기록 등
    log.error("PERMANENT FAILURE - Manual intervention required: {}", command);
}

/**
 * 버퍼 상태 조회 (모니터링용)
 */
public BufferStatus getBufferStatus() {
    return BufferStatus.builder()
            .primaryBufferSize(primaryBuffer.size())
            .retryBufferSize(retryBuffer.size())
            .totalEnqueued(totalEnqueued.get())
            .totalProcessed(totalProcessed.get())
            .totalFailed(totalFailed.get())
            .build();
}
}
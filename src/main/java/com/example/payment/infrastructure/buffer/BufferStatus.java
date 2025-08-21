package com.example.payment.infrastructure.buffer;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BufferStatus {
    private int primaryBufferSize;
    private int retryBufferSize;
    // urgentBufferSize 제거
    private long totalEnqueued;
    private long totalProcessed;
    private long totalFailed;
    private long totalRejected;    // 거부된 명령 수 추가

    /**
     * 성공률 계산
     */
    public double getSuccessRate() {
        if (totalEnqueued == 0) return 0.0;
        return (double) totalProcessed / totalEnqueued * 100;
    }

    /**
     * 실패율 계산
     */
    public double getFailureRate() {
        if (totalEnqueued == 0) return 0.0;
        return (double) totalFailed / totalEnqueued * 100;
    }

    /**
     * 거부율 계산
     */
    public double getRejectionRate() {
        if (totalEnqueued == 0) return 0.0;
        return (double) totalRejected / totalEnqueued * 100;
    }

    /**
     * 전체 버퍼 사용량
     */
    public int getTotalBufferSize() {
        return primaryBufferSize + retryBufferSize;
    }

    /**
     * 처리 대기 중인 명령 수
     */
    public long getPendingCommands() {
        return totalEnqueued - totalProcessed - totalFailed;
    }

    /**
     * 시스템 건강 상태 판단
     */
    public String getHealthStatus() {
        double failureRate = getFailureRate();
        double rejectionRate = getRejectionRate();
        int bufferUsage = getTotalBufferSize();

        if (failureRate > 10 || rejectionRate > 5) {
            return "CRITICAL";
        } else if (failureRate > 5 || rejectionRate > 2 || bufferUsage > 8000) {
            return "WARNING";
        } else {
            return "HEALTHY";
        }
    }

    @Override
    public String toString() {
        return String.format(
                "BufferStatus{buffers=[primary:%d, retry:%d], " +
                        "metrics=[enqueued:%d, processed:%d, failed:%d, rejected:%d], " +
                        "rates=[success:%.1f%%, failure:%.1f%%, rejection:%.1f%%], " +
                        "health:%s}",
                primaryBufferSize, retryBufferSize,
                totalEnqueued, totalProcessed, totalFailed, totalRejected,
                getSuccessRate(), getFailureRate(), getRejectionRate(),
                getHealthStatus()
        );
    }
}
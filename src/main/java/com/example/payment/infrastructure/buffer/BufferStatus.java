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
    private long totalEnqueued;
    private long totalProcessed;
    private long totalFailed;

    public double getSuccessRate() {
        if (totalEnqueued == 0) return 0.0;
        return (double) totalProcessed / totalEnqueued * 100;
    }

    public double getFailureRate() {
        if (totalEnqueued == 0) return 0.0;
        return (double) totalFailed / totalEnqueued * 100;
    }
}
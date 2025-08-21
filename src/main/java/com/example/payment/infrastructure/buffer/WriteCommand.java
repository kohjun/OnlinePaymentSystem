package com.example.payment.infrastructure.buffer;

import java.time.LocalDateTime;

public interface WriteCommand {
    String getCommandId();
    String getType();
    LocalDateTime getCreatedAt();
    Object getPayload();
    int getRetryCount();
    void incrementRetryCount();
    boolean canRetry();
}

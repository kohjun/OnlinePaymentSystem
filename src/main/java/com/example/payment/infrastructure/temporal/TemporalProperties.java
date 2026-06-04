package com.example.payment.infrastructure.temporal;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.temporal")
public class TemporalProperties {
    private boolean enabled = true;
    private boolean workerEnabled = true;
    private String target = "localhost:7233";
    private String namespace = "payment";
    private String taskQueue = "payment-reservation-task-queue";
    private long startToCloseTimeoutSeconds = 30;
    private long resultWaitTimeoutSeconds = 5;
}

package com.example.payment.presentation.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminOperationsQueueResponse {

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime generatedAt;

    private long totalOpen;
    private List<QueueSummary> queues;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QueueSummary {
        private String queue;
        private String label;
        private long count;
        private List<QueueItem> items;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QueueItem {
        private String id;
        private String type;
        private String status;
        private String title;
        private String ownerId;
        private BigDecimal amount;

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        private LocalDateTime createdAt;

        private Map<String, Object> metadata;
        private List<QueueAction> actions;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QueueAction {
        private String action;
        private String label;
        private String tone;
        private boolean noteRequired;
    }
}

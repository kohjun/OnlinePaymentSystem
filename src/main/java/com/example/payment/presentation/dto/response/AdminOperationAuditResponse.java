package com.example.payment.presentation.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminOperationAuditResponse {

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime generatedAt;

    private List<AuditEvent> events;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuditEvent {
        private String eventId;
        private String actorId;
        private String actorRoles;
        private String action;
        private String queue;
        private String resourceId;
        private String outcome;
        private String reason;
        private String ipAddress;
        private String userAgent;

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        private LocalDateTime createdAt;
    }
}

package com.example.payment.domain.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "wal_logs", indexes = {
        @Index(name = "idx_wal_lsn", columnList = "lsn"),
        @Index(name = "idx_wal_transaction", columnList = "transactionId"),
        @Index(name = "idx_wal_status", columnList = "status"),
        @Index(name = "idx_wal_created", columnList = "createdAt")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalLogEntry {

    @Id
    private String logId;

    @Column(unique = true, nullable = false)
    private Long lsn; // Log Sequence Number

    @Column(nullable = false)
    private String transactionId;

    @Column(nullable = false)
    private String operation; // INVENTORY_RESERVE, INVENTORY_CONFIRM, etc.

    @Column(nullable = false)
    private String tableName;

    @Column(columnDefinition = "TEXT")
    private String beforeData; // JSON 형태의 변경 전 데이터

    @Column(columnDefinition = "TEXT")
    private String afterData; // JSON 형태의 변경 후 데이터

    @Column(nullable = false)
    private String status; // PENDING, COMMITTED, FAILED

    private String message;

    private String relatedLogId; // 연관된 다른 WAL 로그 ID

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime writtenAt; // 실제 디스크 기록 시간

    private LocalDateTime updatedAt;

    private LocalDateTime completedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * WAL 로그 상태 열거형
     */
    public enum WalLogStatus {
        PENDING("대기중"),
        IN_PROGRESS("진행중"),
        COMMITTED("커밋됨"),
        FAILED("실패"),
        RECOVERED("복구됨");

        private final String description;

        WalLogStatus(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * WAL 연산 타입 열거형
     */
    public enum WalOperationType {
        PAYMENT_PHASE1_START("결제 1단계 시작"),
        PAYMENT_PHASE2_START("결제 2단계 시작"),
        INVENTORY_RESERVE("재고 예약"),
        INVENTORY_CONFIRM("재고 확정"),
        INVENTORY_CANCEL("재고 취소"),
        ORDER_CREATE("주문 생성"),
        ORDER_UPDATE("주문 업데이트"),
        PAYMENT_PROCESS("결제 처리");

        private final String description;

        WalOperationType(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * 로그 완료 여부 확인
     */
    public boolean isCompleted() {
        return WalLogStatus.COMMITTED.name().equals(status) ||
                WalLogStatus.FAILED.name().equals(status) ||
                WalLogStatus.RECOVERED.name().equals(status);
    }

    /**
     * 로그 실패 여부 확인
     */
    public boolean isFailed() {
        return WalLogStatus.FAILED.name().equals(status);
    }

    /**
     * 로그 처리 중 여부 확인
     */
    public boolean isPending() {
        return WalLogStatus.PENDING.name().equals(status) ||
                WalLogStatus.IN_PROGRESS.name().equals(status);
    }

    /**
     * 로그 소요 시간 계산 (밀리초)
     */
    public long getProcessingDurationMs() {
        if (createdAt == null || completedAt == null) {
            return 0;
        }
        return java.time.Duration.between(createdAt, completedAt).toMillis();
    }

    /**
     * 안전한 문자열 표현 (민감 정보 마스킹)
     */
    public String toSafeString() {
        return String.format(
                "WalLogEntry{logId='%s', lsn=%d, transactionId='%s', operation='%s', status='%s', createdAt=%s}",
                logId, lsn, transactionId, operation, status, createdAt
        );
    }
}
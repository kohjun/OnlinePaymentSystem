package com.example.payment.domain.model.payment;

import com.example.payment.domain.model.common.BaseEntity;
import com.example.payment.domain.model.common.Money;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true) // BaseEntity 포함하여 equals/hashCode 생성
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Payment extends BaseEntity {
    private String paymentId;
    private String orderId;
    private String reservationId;
    private String customerId;
    private Money amount;  // Money 값 객체 사용
    private PaymentMethod method;
    private PaymentStatus status;
    private String transactionId;
    private String failureReason;
    private LocalDateTime processedAt;
    private String approvalNumber; // 승인번호
    private String gatewayName;    // 사용된 게이트웨이 정보

    /**
     * 빌더에서 생성 시간 자동 설정
     */
    public static class PaymentBuilder {
        public Payment build() {
            Payment payment = new Payment(paymentId, orderId, reservationId, customerId,
                    amount, method, status, transactionId, failureReason,
                    processedAt, approvalNumber, gatewayName);

            // 생성 시간 자동 설정
            if (payment.getCreatedAt() == null) {
                payment.onCreate();
            }

            return payment;
        }
    }

    /**
     * 결제 완료 처리
     */
    public void markAsCompleted(String transactionId) {
        this.transactionId = transactionId;
        this.status = PaymentStatus.COMPLETED;
        this.processedAt = LocalDateTime.now();
        this.onUpdate();
    }

    /**
     * 결제 실패 처리
     */
    public void markAsFailed(String reason) {
        this.failureReason = reason;
        this.status = PaymentStatus.FAILED;
        this.processedAt = LocalDateTime.now();
        this.onUpdate();
    }

    /**
     * 처리중 상태로 변경
     */
    public void markAsProcessing() {
        this.status = PaymentStatus.PROCESSING;
        this.processedAt = LocalDateTime.now();
        this.onUpdate();
    }

    /**
     * 환불 처리
     */
    public void markAsRefunded() {
        this.status = PaymentStatus.REFUNDED;
        this.processedAt = LocalDateTime.now();
        this.onUpdate();
    }

    /**
     * 취소 처리
     */
    public void markAsCancelled(String reason) {
        this.failureReason = reason;
        this.status = PaymentStatus.CANCELLED;
        this.processedAt = LocalDateTime.now();
        this.onUpdate();
    }

    // ========================================
    // 비즈니스 로직 메서드들
    // ========================================

    /**
     * 결제 완료 여부
     */
    public boolean isCompleted() {
        return status == PaymentStatus.COMPLETED;
    }

    /**
     * 결제 실패 여부
     */
    public boolean isFailed() {
        return status == PaymentStatus.FAILED;
    }

    /**
     * 처리중 여부
     */
    public boolean isProcessing() {
        return status == PaymentStatus.PROCESSING;
    }

    /**
     * 환불 가능 여부
     */
    public boolean canBeRefunded() {
        return status == PaymentStatus.COMPLETED;
    }

    /**
     * 재시도 가능 여부
     */
    public boolean canBeRetried() {
        return status == PaymentStatus.FAILED || status == PaymentStatus.CANCELLED;
    }

    /**
     * 취소 가능 여부
     */
    public boolean canBeCancelled() {
        return status == PaymentStatus.PROCESSING || status == PaymentStatus.COMPLETED;
    }

    /**
     * 최종 상태 여부 (더 이상 변경되지 않는 상태)
     */
    public boolean isFinalStatus() {
        return status == PaymentStatus.COMPLETED
                || status == PaymentStatus.REFUNDED
                || status == PaymentStatus.CANCELLED;
    }

    /**
     * 결제 정보를 안전하게 로깅하기 위한 메서드 (민감 정보 마스킹)
     */
    public String toSafeString() {
        return String.format(
                "Payment{paymentId='%s', customerId='%s', amount=%s, method=%s, status=%s, processedAt=%s, createdAt=%s}",
                paymentId,
                customerId != null ? maskCustomerId(customerId) : "null",
                amount != null ? amount.getAmount() + " " + amount.getCurrency() : "null",
                method,
                status,
                processedAt,
                createdAt
        );
    }

    /**
     * 고객 ID 마스킹
     */
    private String maskCustomerId(String customerId) {
        if (customerId == null || customerId.length() <= 4) {
            return "***";
        }
        return customerId.substring(0, 4) + "***" + customerId.substring(customerId.length() - 2);
    }

    /**
     * 결제 상태별 한글 설명 반환
     */
    public String getStatusDescription() {
        if (status == null) {
            return "알 수 없음";
        }

        switch (status) {
            case PROCESSING:
                return "처리중";
            case COMPLETED:
                return "완료";
            case FAILED:
                return "실패";
            case REFUNDED:
                return "환불됨";
            case CANCELLED:
                return "취소됨";
            default:
                return status.getDescription();
        }
    }

    /**
     * 결제 경과 시간 (분 단위)
     */
    public long getElapsedMinutes() {
        if (createdAt == null) {
            return 0;
        }
        return java.time.Duration.between(createdAt, LocalDateTime.now()).toMinutes();
    }

    /**
     * 처리 소요 시간 (초 단위)
     */
    public long getProcessingDurationSeconds() {
        if (createdAt == null || processedAt == null) {
            return 0;
        }
        return java.time.Duration.between(createdAt, processedAt).getSeconds();
    }
}
package com.example.payment.domain.model.payment;

import com.example.payment.domain.model.common.BaseEntity;
import com.example.payment.domain.model.common.Money;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Payment extends BaseEntity {
    private String paymentId;
    private String orderId;
    private String reservationId;
    private String customerId;
    private Money amount;
    private PaymentMethod method;
    private PaymentStatus status;
    private String transactionId;
    private String failureReason;
    private LocalDateTime processedAt;
    private String approvalNumber;
    private String gatewayName;

    public static class PaymentBuilder {
        public Payment build() {
            Payment payment = new Payment(paymentId, orderId, reservationId, customerId,
                    amount, method, status, transactionId, failureReason,
                    processedAt, approvalNumber, gatewayName);
            if (payment.getCreatedAt() == null) {
                payment.onCreate();
            }
            return payment;
        }
    }

    public void markAsCompleted(String transactionId) {
        this.transactionId = transactionId;
        this.status = PaymentStatus.COMPLETED;
        this.processedAt = LocalDateTime.now();
        this.onUpdate();
    }

    public void markAsApproved(String transactionId) {
        this.transactionId = transactionId;
        this.status = PaymentStatus.APPROVED;
        this.processedAt = LocalDateTime.now();
        this.onUpdate();
    }

    public void markAsFailed(String reason) {
        this.failureReason = reason;
        this.status = PaymentStatus.FAILED;
        this.processedAt = LocalDateTime.now();
        this.onUpdate();
    }

    public void markAsUnknown(String reason) {
        this.failureReason = reason;
        this.status = PaymentStatus.UNKNOWN;
        this.processedAt = LocalDateTime.now();
        this.onUpdate();
    }

    public void markAsProcessing() {
        this.status = PaymentStatus.PROCESSING;
        this.processedAt = LocalDateTime.now();
        this.onUpdate();
    }

    public void markAsRefunded() {
        this.status = PaymentStatus.REFUNDED;
        this.processedAt = LocalDateTime.now();
        this.onUpdate();
    }

    public void markRefundFailed(String reason) {
        this.failureReason = reason;
        this.status = PaymentStatus.REFUND_FAILED;
        this.processedAt = LocalDateTime.now();
        this.onUpdate();
    }

    public void markAsCancelled(String reason) {
        this.failureReason = reason;
        this.status = PaymentStatus.CANCELLED;
        this.processedAt = LocalDateTime.now();
        this.onUpdate();
    }

    public boolean isCompleted() {
        return status == PaymentStatus.APPROVED || status == PaymentStatus.COMPLETED;
    }

    public boolean isFailed() {
        return status == PaymentStatus.FAILED;
    }

    public boolean isProcessing() {
        return status == PaymentStatus.PROCESSING;
    }

    public boolean canBeRefunded() {
        return status == PaymentStatus.APPROVED || status == PaymentStatus.COMPLETED;
    }

    public boolean canBeRetried() {
        return status == PaymentStatus.FAILED || status == PaymentStatus.CANCELLED;
    }

    public boolean canBeCancelled() {
        return status == PaymentStatus.PROCESSING
                || status == PaymentStatus.APPROVED
                || status == PaymentStatus.COMPLETED
                || status == PaymentStatus.UNKNOWN;
    }

    public boolean isFinalStatus() {
        return status == PaymentStatus.APPROVED
                || status == PaymentStatus.COMPLETED
                || status == PaymentStatus.REFUNDED
                || status == PaymentStatus.REFUND_FAILED
                || status == PaymentStatus.CANCELLED;
    }

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

    private String maskCustomerId(String customerId) {
        if (customerId == null || customerId.length() <= 4) {
            return "***";
        }
        return customerId.substring(0, 4) + "***" + customerId.substring(customerId.length() - 2);
    }

    public String getStatusDescription() {
        return status == null ? "unknown" : status.getDescription();
    }

    public long getElapsedMinutes() {
        if (createdAt == null) {
            return 0;
        }
        return java.time.Duration.between(createdAt, LocalDateTime.now()).toMinutes();
    }

    public long getProcessingDurationSeconds() {
        if (createdAt == null || processedAt == null) {
            return 0;
        }
        return java.time.Duration.between(createdAt, processedAt).getSeconds();
    }
}

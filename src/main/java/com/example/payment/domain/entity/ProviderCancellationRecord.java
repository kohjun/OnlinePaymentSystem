package com.example.payment.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "provider_cancellations", uniqueConstraints = @UniqueConstraint(columnNames = {"gateway_name", "transaction_key"}))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ProviderCancellationRecord {
    @Id private String receiptId;
    @Column(nullable = false) private String paymentId;
    @Column(nullable = false) private String gatewayName;
    @Column(nullable = false) private String transactionKey;
    @Column(nullable = false, precision = 19, scale = 2) private BigDecimal amount;
    @Column(nullable = false) private LocalDateTime observedAt;
}

package com.example.payment.infrastructure.persistence.wal;

import com.example.payment.domain.entity.WalLogEntry;

import java.time.LocalDateTime;

public interface WalBackupService {
    void createBackupCopy(WalLogEntry logEntry);
    void verifyBackupIntegrity(WalLogEntry logEntry);
    void cleanupOldBackups(LocalDateTime before);
}
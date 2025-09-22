package com.example.payment.infrastructure.persistence.wal.impl;

import com.example.payment.domain.entity.WalLogEntry;
import com.example.payment.infrastructure.persistence.wal.WalBackupService;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;

@Slf4j
public class DefaultWalBackupService implements WalBackupService {

    @Override
    public void createBackupCopy(WalLogEntry logEntry) {
        log.debug("Creating backup copy for WAL log: logId={}", logEntry.getLogId());

        try {
            // 실제 구현에서는 별도 백업 스토리지로 복제
            // backupStorage.store(logEntry);

        } catch (Exception e) {
            log.error("Failed to create backup copy: logId={}", logEntry.getLogId(), e);
        }
    }

    @Override
    public void verifyBackupIntegrity(WalLogEntry logEntry) {
        log.debug("Verifying backup integrity: logId={}", logEntry.getLogId());

        try {
            // 체크섬 검증 등
            // backupStorage.verify(logEntry.getLogId());

        } catch (Exception e) {
            log.error("Backup integrity verification failed: logId={}", logEntry.getLogId(), e);
        }
    }

    @Override
    public void cleanupOldBackups(LocalDateTime before) {
        log.info("Cleaning up old backups before: {}", before);

        try {
            // 오래된 백업 정리
            // backupStorage.cleanup(before);

        } catch (Exception e) {
            log.error("Failed to cleanup old backups", e);
        }
    }
}
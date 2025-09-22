package com.example.payment.infrastructure.persistence.wal.impl;

import com.example.payment.domain.entity.WalLogEntry;
import com.example.payment.infrastructure.persistence.wal.WalArchiveService;
import com.example.payment.config.WalProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class DefaultWalArchiveService implements WalArchiveService {

    private final WalProperties.Archive archiveConfig;

    @Override
    public void archiveToStorage(List<WalLogEntry> logs) {
        log.info("Archiving {} WAL logs to storage", logs.size());

        try {
            // 실제 구현에서는 S3, GCS 등 외부 스토리지로 이동
            for (WalLogEntry log : logs) {
                // archiveLog(log);
              //  log.debug("Archived WAL log: logId={}", log.getLogId());
            }

        } catch (Exception e) {
            log.error("Failed to archive WAL logs to storage", e);
            throw new RuntimeException("Archive operation failed", e);
        }
    }

    @Override
    public void compressArchivedLogs(List<WalLogEntry> logs) {
        if (archiveConfig.isCompressArchivedLogs()) {
            log.info("Compressing {} archived WAL logs", logs.size());

            try {
                // 압축 로직 구현
                // compressLogs(logs);

            } catch (Exception e) {
                log.error("Failed to compress archived WAL logs", e);
            }
        }
    }

    @Override
    public void validateArchivedLogs(List<WalLogEntry> logs) {
        log.debug("Validating {} archived WAL logs", logs.size());

        try {
            // 체크섬 검증 로직
            for (WalLogEntry log : logs) {
                // validateChecksum(log);
            }

        } catch (Exception e) {
            log.error("WAL log validation failed", e);
            throw new RuntimeException("Archive validation failed", e);
        }
    }
}
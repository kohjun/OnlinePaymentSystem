package com.example.payment.infrastructure.persistence.wal;

import com.example.payment.domain.entity.WalLogEntry;

import java.util.List;

public interface WalArchiveService {
    void archiveToStorage(List<WalLogEntry> logs);
    void compressArchivedLogs(List<WalLogEntry> logs);
    void validateArchivedLogs(List<WalLogEntry> logs);
}

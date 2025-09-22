-- WAL 로그 테이블 (MySQL 버전)
CREATE TABLE IF NOT EXISTS wal_logs (
                                        log_id VARCHAR(255) PRIMARY KEY,
    lsn BIGINT NOT NULL AUTO_INCREMENT UNIQUE,
    transaction_id VARCHAR(255) NOT NULL,
    operation VARCHAR(100) NOT NULL,
    table_name VARCHAR(100) NOT NULL,
    before_data TEXT,
    after_data TEXT,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL ON UPDATE CURRENT_TIMESTAMP,
    completed_at TIMESTAMP NULL,

    INDEX idx_wal_lsn (lsn),
    INDEX idx_wal_transaction (transaction_id),
    INDEX idx_wal_status (status),
    INDEX idx_wal_created (created_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- MySQL용 간단한 정리 프로시저
DELIMITER //
CREATE PROCEDURE IF NOT EXISTS cleanup_wal_logs(IN days_old INT)
BEGIN
DELETE FROM wal_logs
WHERE status IN ('COMMITTED', 'FAILED')
  AND completed_at < DATE_SUB(NOW(), INTERVAL days_old DAY);

SELECT ROW_COUNT() as deleted_count;
END //
DELIMITER ;
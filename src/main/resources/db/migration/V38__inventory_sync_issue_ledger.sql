CREATE TABLE IF NOT EXISTS inventory_sync_issues (
    issue_id VARCHAR(80) PRIMARY KEY,
    reservation_id VARCHAR(100) NOT NULL,
    product_id VARCHAR(100) NOT NULL,
    quantity INTEGER NOT NULL,
    issue_type VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL,
    failure_reason TEXT,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    resolved_at TIMESTAMP,
    CONSTRAINT chk_inventory_sync_issue_quantity CHECK (quantity > 0),
    CONSTRAINT chk_inventory_sync_issue_type CHECK (issue_type IN (
        'RESERVE_RELEASE_REQUIRED', 'CONFIRM_DB_APPLY_REQUIRED', 'RELEASE_DB_APPLY_REQUIRED'
    )),
    CONSTRAINT chk_inventory_sync_issue_status CHECK (status IN ('PENDING', 'RESOLVED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_inventory_sync_issues_pending
    ON inventory_sync_issues(status, created_at);

CREATE TABLE IF NOT EXISTS marketplace_reports (
    report_id VARCHAR(64) PRIMARY KEY,
    reporter_user_id VARCHAR(100) NOT NULL,
    reporter_customer_id VARCHAR(100) NOT NULL,
    target_type VARCHAR(40) NOT NULL,
    target_id VARCHAR(100) NOT NULL,
    reason VARCHAR(40) NOT NULL,
    details VARCHAR(2000),
    status VARCHAR(40) NOT NULL DEFAULT 'OPEN',
    reviewed_by VARCHAR(100),
    review_note VARCHAR(2000),
    resolved_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    CONSTRAINT ck_marketplace_reports_target_type CHECK (target_type IN ('LISTING', 'SELLER', 'SALE_EVENT', 'USER', 'ORDER')),
    CONSTRAINT ck_marketplace_reports_reason CHECK (reason IN ('COUNTERFEIT', 'PROHIBITED_ITEM', 'FRAUD', 'MISLEADING', 'ABUSE', 'PAYMENT_ISSUE', 'OTHER')),
    CONSTRAINT ck_marketplace_reports_status CHECK (status IN ('OPEN', 'IN_REVIEW', 'RESOLVED', 'REJECTED'))
);

CREATE INDEX IF NOT EXISTS ix_marketplace_reports_reporter
    ON marketplace_reports (reporter_user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS ix_marketplace_reports_status
    ON marketplace_reports (status, created_at ASC);

CREATE INDEX IF NOT EXISTS ix_marketplace_reports_target
    ON marketplace_reports (target_type, target_id);

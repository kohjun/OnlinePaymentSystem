CREATE TABLE IF NOT EXISTS seller_payout_accounts (
    payout_account_id VARCHAR(64) PRIMARY KEY,
    seller_id VARCHAR(255) NOT NULL,
    account_ref VARCHAR(500) NOT NULL,
    bank_code VARCHAR(50) NOT NULL,
    bank_name VARCHAR(100) NOT NULL,
    account_holder_name VARCHAR(100) NOT NULL,
    account_last4 VARCHAR(4) NOT NULL,
    status VARCHAR(50) NOT NULL,
    review_note VARCHAR(1000),
    submitted_at TIMESTAMP NOT NULL,
    reviewed_by VARCHAR(100),
    reviewed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    CONSTRAINT fk_seller_payout_accounts_seller
        FOREIGN KEY (seller_id) REFERENCES sellers(seller_id),
    CONSTRAINT chk_seller_payout_accounts_last4
        CHECK (LENGTH(account_last4) = 4),
    CONSTRAINT chk_seller_payout_accounts_status
        CHECK (status IN ('PENDING_REVIEW', 'VERIFIED', 'REJECTED'))
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_seller_payout_accounts_seller
    ON seller_payout_accounts (seller_id);

CREATE INDEX IF NOT EXISTS ix_seller_payout_accounts_status
    ON seller_payout_accounts (status, submitted_at);

CREATE TABLE IF NOT EXISTS seller_payout_transfers (
    transfer_id VARCHAR(64) PRIMARY KEY,
    payout_id VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    amount DECIMAL(19, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(30) NOT NULL,
    provider VARCHAR(100) NOT NULL,
    provider_transfer_id VARCHAR(255),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    failure_reason VARCHAR(1000),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    CONSTRAINT fk_seller_payout_transfer_payout
        FOREIGN KEY (payout_id) REFERENCES seller_payouts(payout_id),
    CONSTRAINT uq_seller_payout_transfer_idempotency
        UNIQUE (payout_id, idempotency_key),
    CONSTRAINT chk_seller_payout_transfer_amount
        CHECK (amount >= 0),
    CONSTRAINT chk_seller_payout_transfer_currency
        CHECK (LENGTH(currency) = 3 AND currency = UPPER(currency)),
    CONSTRAINT chk_seller_payout_transfer_attempt_count
        CHECK (attempt_count >= 0),
    CONSTRAINT chk_seller_payout_transfer_status
        CHECK (status IN ('CREATED', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'UNKNOWN'))
);

CREATE INDEX IF NOT EXISTS ix_seller_payout_transfers_status_updated
    ON seller_payout_transfers (status, updated_at);

CREATE UNIQUE INDEX IF NOT EXISTS ux_seller_payout_transfers_provider_id
    ON seller_payout_transfers (provider, provider_transfer_id);

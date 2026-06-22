CREATE TABLE IF NOT EXISTS payment_idempotencies (
    idempotency_id VARCHAR(255) PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    merchant_id VARCHAR(128) NOT NULL,
    operation VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash VARCHAR(128) NOT NULL,
    workflow_id VARCHAR(255),
    status VARCHAR(50) NOT NULL,
    response_body TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    CONSTRAINT ux_payment_idempotencies_scope_key
        UNIQUE (tenant_id, merchant_id, operation, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_payment_idempotencies_expires
    ON payment_idempotencies(expires_at);

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS unit_price DECIMAL(19, 2);

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS price_source VARCHAR(100);

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS price_calculated_at TIMESTAMP;

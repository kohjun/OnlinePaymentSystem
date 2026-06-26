CREATE TABLE IF NOT EXISTS toss_webhook_events (
    event_id VARCHAR(80) PRIMARY KEY,
    dedupe_key VARCHAR(255) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payment_key VARCHAR(255),
    order_id VARCHAR(255),
    payment_status VARCHAR(80),
    raw_payload TEXT NOT NULL,
    processing_status VARCHAR(50) NOT NULL,
    failure_reason TEXT,
    attempt_count INT NOT NULL DEFAULT 0,
    received_at TIMESTAMP NOT NULL,
    processed_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_toss_webhook_events_dedupe_key
    ON toss_webhook_events(dedupe_key);

CREATE INDEX IF NOT EXISTS ix_toss_webhook_events_status_received
    ON toss_webhook_events(processing_status, received_at);

CREATE INDEX IF NOT EXISTS ix_toss_webhook_events_payment_key
    ON toss_webhook_events(payment_key);

CREATE INDEX IF NOT EXISTS ix_toss_webhook_events_order_id
    ON toss_webhook_events(order_id);

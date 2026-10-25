ALTER TABLE refunds ADD COLUMN source_type VARCHAR(40);
ALTER TABLE refunds ADD COLUMN source_id VARCHAR(255);

CREATE TABLE provider_cancellations (
    receipt_id VARCHAR(255) PRIMARY KEY,
    payment_id VARCHAR(255) NOT NULL REFERENCES payments(payment_id),
    gateway_name VARCHAR(100) NOT NULL,
    transaction_key VARCHAR(255) NOT NULL,
    amount NUMERIC(19,2) NOT NULL CHECK (amount >= 0),
    observed_at TIMESTAMP NOT NULL,
    UNIQUE (gateway_name, transaction_key)
);
CREATE INDEX ix_provider_cancellations_payment ON provider_cancellations(payment_id);
ALTER TABLE refunds ADD COLUMN active_payment_id VARCHAR(255);
UPDATE refunds SET active_payment_id = payment_id WHERE status IN ('PROCESSING', 'UNKNOWN');
CREATE UNIQUE INDEX ux_refunds_active_payment ON refunds(active_payment_id);
ALTER TABLE refunds ADD CONSTRAINT ck_refunds_active_payment CHECK (
    (status IN ('PROCESSING', 'UNKNOWN') AND active_payment_id IS NOT NULL AND active_payment_id = payment_id)
    OR (status NOT IN ('PROCESSING', 'UNKNOWN') AND active_payment_id IS NULL)
);

ALTER TABLE toss_webhook_events ADD COLUMN lease_token VARCHAR(100);
ALTER TABLE toss_webhook_events ADD COLUMN lease_until TIMESTAMP;
CREATE INDEX ix_webhook_recovery ON toss_webhook_events(processing_status, lease_until);

ALTER TABLE marketplace_orders DROP CONSTRAINT IF EXISTS chk_marketplace_orders_status;
ALTER TABLE marketplace_orders ADD CONSTRAINT chk_marketplace_orders_status
    CHECK (status IN ('PENDING', 'PAID', 'FAILED', 'CANCELLED', 'REFUNDED', 'PARTIALLY_REFUNDED', 'REFUND_FAILED', 'REFUND_UNKNOWN'));

ALTER TABLE inventory_sync_issues DROP CONSTRAINT IF EXISTS chk_inventory_sync_issue_type;
ALTER TABLE inventory_sync_issues ADD CONSTRAINT chk_inventory_sync_issue_type CHECK (issue_type IN (
    'RESERVE_RELEASE_REQUIRED', 'CONFIRM_DB_APPLY_REQUIRED', 'RELEASE_DB_APPLY_REQUIRED',
    'ORDER_CANCELLATION_RESTORE_REQUIRED', 'ROLLBACK_RELEASE_REQUIRED',
    'RELEASE_REDIS_REQUIRED', 'RESTORE_REDIS_REQUIRED'
));

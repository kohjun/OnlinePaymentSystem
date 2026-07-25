ALTER TABLE payments DROP CONSTRAINT IF EXISTS chk_payments_status;
ALTER TABLE payments
    ADD CONSTRAINT chk_payments_status
    CHECK (status IN (
        'CREATED', 'PROCESSING', 'APPROVED', 'COMPLETED', 'FAILED', 'UNKNOWN',
        'REFUNDED', 'PARTIALLY_REFUNDED', 'REFUND_UNKNOWN', 'REFUND_FAILED',
        'CANCELLED', 'CANCELED'
    ));

ALTER TABLE refunds DROP CONSTRAINT IF EXISTS chk_refunds_status;
ALTER TABLE refunds
    ADD CONSTRAINT chk_refunds_status
    CHECK (status IN ('PROCESSING', 'SUCCEEDED', 'FAILED', 'UNKNOWN'));

CREATE INDEX IF NOT EXISTS idx_refunds_unknown_updated
    ON refunds(status, updated_at);

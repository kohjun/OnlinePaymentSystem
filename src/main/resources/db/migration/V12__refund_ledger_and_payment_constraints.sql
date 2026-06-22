CREATE TABLE IF NOT EXISTS refunds (
    refund_id VARCHAR(255) PRIMARY KEY,
    payment_id VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    amount DECIMAL(19, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(50) NOT NULL,
    provider_refund_id VARCHAR(255),
    failure_reason TEXT,
    attempt_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    completed_at TIMESTAMP,
    CONSTRAINT ux_refunds_payment_idempotency UNIQUE (payment_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_refunds_payment_id
    ON refunds(payment_id);

CREATE INDEX IF NOT EXISTS idx_refunds_status
    ON refunds(status);

CREATE UNIQUE INDEX IF NOT EXISTS ux_payments_transaction_id
    ON payments(transaction_id);

CREATE UNIQUE INDEX IF NOT EXISTS ux_payments_gateway_transaction
    ON payments(gateway_name, transaction_id);

CREATE UNIQUE INDEX IF NOT EXISTS ux_orders_reservation_id
    ON orders(reservation_id);

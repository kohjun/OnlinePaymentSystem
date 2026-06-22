CREATE TABLE IF NOT EXISTS toss_payment_intents (
    intent_id VARCHAR(255) PRIMARY KEY,
    order_id VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(300) NOT NULL,
    request_hash VARCHAR(128) NOT NULL,
    customer_id VARCHAR(255) NOT NULL,
    product_id VARCHAR(255) NOT NULL,
    quantity INT NOT NULL,
    amount DECIMAL(19, 2) NOT NULL,
    currency VARCHAR(10) NOT NULL,
    payment_method VARCHAR(50) NOT NULL,
    order_name VARCHAR(100) NOT NULL,
    customer_key VARCHAR(255) NOT NULL,
    merchant_id VARCHAR(255),
    client_id VARCHAR(255),
    seat_id VARCHAR(50),
    status VARCHAR(50) NOT NULL,
    payment_key VARCHAR(255),
    workflow_id VARCHAR(255),
    response_body TEXT,
    success_url TEXT,
    fail_url TEXT,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_toss_payment_intents_order_id
    ON toss_payment_intents(order_id);

CREATE UNIQUE INDEX IF NOT EXISTS ux_toss_payment_intents_idempotency_key
    ON toss_payment_intents(idempotency_key);

CREATE INDEX IF NOT EXISTS ix_toss_payment_intents_customer_status
    ON toss_payment_intents(customer_id, status);

CREATE TABLE IF NOT EXISTS shipping_addresses (
    address_id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL,
    customer_id VARCHAR(100) NOT NULL,
    label VARCHAR(100),
    recipient_name VARCHAR(100) NOT NULL,
    contact_phone VARCHAR(50) NOT NULL,
    postal_code VARCHAR(30),
    address_line1 VARCHAR(500) NOT NULL,
    address_line2 VARCHAR(500),
    delivery_memo VARCHAR(500),
    default_address BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(40) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    CONSTRAINT ck_shipping_addresses_status CHECK (status IN ('ACTIVE', 'DELETED'))
);

CREATE INDEX IF NOT EXISTS ix_shipping_addresses_user_status
    ON shipping_addresses (user_id, status, default_address, created_at DESC);

CREATE INDEX IF NOT EXISTS ix_shipping_addresses_customer
    ON shipping_addresses (customer_id, status);

ALTER TABLE marketplace_orders ADD COLUMN IF NOT EXISTS shipping_address_id VARCHAR(64);
ALTER TABLE marketplace_orders ADD COLUMN IF NOT EXISTS shipping_recipient_name VARCHAR(100);
ALTER TABLE marketplace_orders ADD COLUMN IF NOT EXISTS shipping_contact_phone VARCHAR(50);
ALTER TABLE marketplace_orders ADD COLUMN IF NOT EXISTS shipping_postal_code VARCHAR(30);
ALTER TABLE marketplace_orders ADD COLUMN IF NOT EXISTS shipping_address VARCHAR(1000);
ALTER TABLE marketplace_orders ADD COLUMN IF NOT EXISTS shipping_method VARCHAR(50);
ALTER TABLE marketplace_orders ADD COLUMN IF NOT EXISTS shipping_memo VARCHAR(1000);

ALTER TABLE toss_payment_intents ADD COLUMN IF NOT EXISTS shipping_address_id VARCHAR(64);
ALTER TABLE toss_payment_intents ADD COLUMN IF NOT EXISTS shipping_recipient_name VARCHAR(100);
ALTER TABLE toss_payment_intents ADD COLUMN IF NOT EXISTS shipping_contact_phone VARCHAR(50);
ALTER TABLE toss_payment_intents ADD COLUMN IF NOT EXISTS shipping_postal_code VARCHAR(30);
ALTER TABLE toss_payment_intents ADD COLUMN IF NOT EXISTS shipping_address VARCHAR(1000);
ALTER TABLE toss_payment_intents ADD COLUMN IF NOT EXISTS shipping_method VARCHAR(50);
ALTER TABLE toss_payment_intents ADD COLUMN IF NOT EXISTS shipping_memo VARCHAR(1000);

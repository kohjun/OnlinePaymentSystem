ALTER TABLE marketplace_orders
    ADD COLUMN IF NOT EXISTS shipped_at TIMESTAMP;

ALTER TABLE marketplace_orders
    ADD COLUMN IF NOT EXISTS tracking_carrier VARCHAR(100);

ALTER TABLE marketplace_orders
    ADD COLUMN IF NOT EXISTS tracking_number VARCHAR(100);

CREATE INDEX IF NOT EXISTS ix_marketplace_orders_tracking
    ON marketplace_orders (tracking_carrier, tracking_number);

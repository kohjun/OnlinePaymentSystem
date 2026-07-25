ALTER TABLE marketplace_orders
    ADD COLUMN IF NOT EXISTS buyer_confirmed_at TIMESTAMP;

ALTER TABLE marketplace_orders
    ADD COLUMN IF NOT EXISTS disputed_at TIMESTAMP;

ALTER TABLE marketplace_orders
    ADD COLUMN IF NOT EXISTS dispute_reason VARCHAR(1000);

CREATE INDEX IF NOT EXISTS ix_marketplace_orders_confirmation
    ON marketplace_orders (buyer_confirmed_at, disputed_at);

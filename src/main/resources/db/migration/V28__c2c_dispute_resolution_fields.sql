ALTER TABLE marketplace_orders ADD COLUMN IF NOT EXISTS dispute_resolution VARCHAR(50);
ALTER TABLE marketplace_orders ADD COLUMN IF NOT EXISTS dispute_resolution_note VARCHAR(1000);
ALTER TABLE marketplace_orders ADD COLUMN IF NOT EXISTS dispute_resolved_by VARCHAR(100);
ALTER TABLE marketplace_orders ADD COLUMN IF NOT EXISTS dispute_resolved_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS ix_marketplace_orders_dispute_resolution
    ON marketplace_orders (disputed_at, dispute_resolved_at, dispute_resolution);

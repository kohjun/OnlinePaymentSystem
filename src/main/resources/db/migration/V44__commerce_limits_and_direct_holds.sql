ALTER TABLE sale_events ADD COLUMN IF NOT EXISTS max_quantity_per_customer INT;
UPDATE sale_events
SET max_quantity_per_customer = CASE
    WHEN sale_type IN ('DROP', 'RAFFLE', 'AUCTION') THEN 1
    ELSE stock_quantity
END
WHERE max_quantity_per_customer IS NULL;
ALTER TABLE sale_events ALTER COLUMN max_quantity_per_customer SET NOT NULL;
ALTER TABLE sale_events ADD CONSTRAINT chk_sale_event_customer_limit
    CHECK (max_quantity_per_customer > 0 AND max_quantity_per_customer <= stock_quantity);

ALTER TABLE inventory_reservations DROP CONSTRAINT IF EXISTS chk_inventory_reservation_origin;
ALTER TABLE inventory_reservations ADD CONSTRAINT chk_inventory_reservation_origin
    CHECK (origin IN ('CHECKOUT', 'DIRECT_INTENT', 'RAFFLE_WINNER', 'AUCTION_WINNER'));

CREATE INDEX IF NOT EXISTS idx_toss_intent_customer_event_active
    ON toss_payment_intents(sale_event_id, customer_id, status, expires_at);
CREATE INDEX IF NOT EXISTS idx_toss_intent_ready_expiry
    ON toss_payment_intents(status, expires_at);

ALTER TABLE auction_bids ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(160);

CREATE UNIQUE INDEX IF NOT EXISTS ux_auction_bid_customer_idempotency
    ON auction_bids(sale_event_id, customer_id, idempotency_key);

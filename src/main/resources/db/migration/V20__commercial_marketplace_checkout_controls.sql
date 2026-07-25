ALTER TABLE raffle_winners
    ADD COLUMN IF NOT EXISTS checkout_expires_at TIMESTAMP;

ALTER TABLE auction_settlements
    ADD COLUMN IF NOT EXISTS checkout_expires_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS ix_toss_payment_intents_active_marketplace
    ON toss_payment_intents(sale_event_id, marketplace_checkout_type, status, expires_at);

CREATE INDEX IF NOT EXISTS ix_raffle_winners_checkout_expiry
    ON raffle_winners(sale_event_id, checkout_status, checkout_expires_at);

CREATE INDEX IF NOT EXISTS ix_auction_settlements_checkout_expiry
    ON auction_settlements(sale_event_id, status, checkout_expires_at);

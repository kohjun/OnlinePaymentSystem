ALTER TABLE toss_payment_intents
    ADD COLUMN IF NOT EXISTS sale_event_id VARCHAR(255);

ALTER TABLE toss_payment_intents
    ADD COLUMN IF NOT EXISTS listing_id VARCHAR(255);

ALTER TABLE toss_payment_intents
    ADD COLUMN IF NOT EXISTS marketplace_checkout_type VARCHAR(50);

ALTER TABLE toss_payment_intents
    ADD COLUMN IF NOT EXISTS marketplace_source_id VARCHAR(255);

CREATE INDEX IF NOT EXISTS ix_toss_payment_intents_marketplace_event
    ON toss_payment_intents(sale_event_id, marketplace_checkout_type);

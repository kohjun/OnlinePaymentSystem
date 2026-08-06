ALTER TABLE inventory_reservations ADD COLUMN IF NOT EXISTS origin VARCHAR(40) DEFAULT 'CHECKOUT' NOT NULL;
ALTER TABLE inventory_reservations ADD COLUMN IF NOT EXISTS source_type VARCHAR(50);
ALTER TABLE inventory_reservations ADD COLUMN IF NOT EXISTS source_id VARCHAR(255);
ALTER TABLE inventory_reservations ADD COLUMN IF NOT EXISTS claimed_intent_id VARCHAR(255);

ALTER TABLE raffle_entries ADD COLUMN IF NOT EXISTS draw_rank INT;
ALTER TABLE raffle_winners ADD COLUMN IF NOT EXISTS offer_sequence INT DEFAULT 1 NOT NULL;
ALTER TABLE raffle_winners ADD COLUMN IF NOT EXISTS inventory_reservation_id VARCHAR(255);
ALTER TABLE auction_settlements ADD COLUMN IF NOT EXISTS offer_sequence INT DEFAULT 1 NOT NULL;
ALTER TABLE auction_settlements ADD COLUMN IF NOT EXISTS inventory_reservation_id VARCHAR(255);
ALTER TABLE toss_payment_intents ADD COLUMN IF NOT EXISTS inventory_reservation_id VARCHAR(255);

ALTER TABLE auction_settlements DROP CONSTRAINT IF EXISTS uq_auction_settlement_sale_event;
ALTER TABLE auction_settlements ADD CONSTRAINT uq_auction_settlement_event_offer
    UNIQUE (sale_event_id, offer_sequence);
ALTER TABLE auction_settlements ADD CONSTRAINT uq_auction_settlement_event_bid
    UNIQUE (sale_event_id, winning_bid_id);

ALTER TABLE auction_bids DROP CONSTRAINT IF EXISTS chk_auction_bid_status;
ALTER TABLE auction_bids ADD CONSTRAINT chk_auction_bid_status
    CHECK (status IN ('ACCEPTED', 'OUTBID', 'WINNING', 'OFFER_EXPIRED', 'RESERVE_NOT_MET', 'CANCELLED'));
ALTER TABLE auction_settlements DROP CONSTRAINT IF EXISTS chk_auction_settlement_status;
ALTER TABLE auction_settlements ADD CONSTRAINT chk_auction_settlement_status
    CHECK (status IN ('AWAITING_PAYMENT', 'PAID', 'EXPIRED', 'CANCELLED'));
ALTER TABLE raffle_entries DROP CONSTRAINT IF EXISTS chk_raffle_entry_status;
ALTER TABLE raffle_entries ADD CONSTRAINT chk_raffle_entry_status
    CHECK (status IN ('ENTERED', 'WINNER', 'WAITLISTED', 'NOT_SELECTED', 'CANCELLED'));

ALTER TABLE inventory_reservations ADD CONSTRAINT chk_inventory_reservation_origin
    CHECK (origin IN ('CHECKOUT', 'RAFFLE_WINNER', 'AUCTION_WINNER'));
CREATE UNIQUE INDEX IF NOT EXISTS ux_inventory_reservation_source
    ON inventory_reservations(source_type, source_id);
CREATE INDEX IF NOT EXISTS idx_raffle_entries_draw_rank
    ON raffle_entries(sale_event_id, status, draw_rank);
CREATE INDEX IF NOT EXISTS idx_raffle_winners_expiry
    ON raffle_winners(checkout_status, checkout_expires_at);
CREATE INDEX IF NOT EXISTS idx_auction_settlements_expiry
    ON auction_settlements(status, checkout_expires_at);

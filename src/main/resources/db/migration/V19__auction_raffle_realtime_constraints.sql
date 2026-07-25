WITH ranked_settlements AS (
    SELECT
        settlement_id,
        ROW_NUMBER() OVER (
            PARTITION BY sale_event_id
            ORDER BY created_at ASC, settlement_id ASC
        ) AS row_no
    FROM auction_settlements
)
DELETE FROM auction_settlements
WHERE settlement_id IN (
    SELECT settlement_id
    FROM ranked_settlements
    WHERE row_no > 1
);

WITH ranked_winners AS (
    SELECT
        winner_id,
        ROW_NUMBER() OVER (
            PARTITION BY sale_event_id, customer_id
            ORDER BY created_at ASC, winner_id ASC
        ) AS row_no
    FROM raffle_winners
)
DELETE FROM raffle_winners
WHERE winner_id IN (
    SELECT winner_id
    FROM ranked_winners
    WHERE row_no > 1
);

-- H2 and Postgres compatible standard ALTER TABLE ADD CONSTRAINT statements
ALTER TABLE auction_settlements ADD CONSTRAINT uq_auction_settlement_sale_event UNIQUE (sale_event_id);
ALTER TABLE raffle_winners ADD CONSTRAINT uq_raffle_winner_sale_event_customer UNIQUE (sale_event_id, customer_id);
ALTER TABLE auction_bids ADD CONSTRAINT chk_auction_bid_amount_positive CHECK (bid_amount > 0);
ALTER TABLE auction_bids ADD CONSTRAINT chk_auction_bid_status CHECK (status IN ('ACCEPTED', 'OUTBID', 'WINNING', 'CANCELLED'));
ALTER TABLE auction_settlements ADD CONSTRAINT chk_auction_settlement_amount_nonnegative CHECK (amount >= 0);
ALTER TABLE auction_settlements ADD CONSTRAINT chk_auction_settlement_status CHECK (status IN ('AWAITING_PAYMENT', 'PAID', 'CANCELLED'));
ALTER TABLE raffle_entries ADD CONSTRAINT chk_raffle_entry_status CHECK (status IN ('ENTERED', 'WINNER', 'NOT_SELECTED', 'CANCELLED'));
ALTER TABLE raffle_winners ADD CONSTRAINT chk_raffle_winner_checkout_status CHECK (checkout_status IN ('PENDING', 'COMPLETED', 'EXPIRED'));
ALTER TABLE sale_events ADD CONSTRAINT chk_sale_event_realtime_price_nonnegative CHECK (price >= 0);
ALTER TABLE sale_events ADD CONSTRAINT chk_sale_event_realtime_stock_positive CHECK (stock_quantity > 0);
ALTER TABLE sale_events ADD CONSTRAINT chk_sale_event_realtime_status CHECK (status IN ('SCHEDULED', 'LIVE', 'ENDED', 'CANCELLED'));

CREATE INDEX IF NOT EXISTS idx_sale_events_due_auction
    ON sale_events(sale_type, status, ends_at);

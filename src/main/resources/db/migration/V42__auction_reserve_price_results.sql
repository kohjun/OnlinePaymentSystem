ALTER TABLE sale_events ADD COLUMN IF NOT EXISTS close_reason VARCHAR(50);

UPDATE sale_events
SET stock_quantity = 1
WHERE sale_type = 'AUCTION' AND stock_quantity <> 1;

UPDATE sale_events
SET min_bid_increment = 1000
WHERE sale_type = 'AUCTION' AND (min_bid_increment IS NULL OR min_bid_increment <= 0);

UPDATE sale_events
SET reserve_price = price
WHERE sale_type = 'AUCTION' AND reserve_price IS NOT NULL AND reserve_price < price;

ALTER TABLE auction_bids DROP CONSTRAINT IF EXISTS chk_auction_bid_status;
ALTER TABLE auction_bids ADD CONSTRAINT chk_auction_bid_status
    CHECK (status IN ('ACCEPTED', 'OUTBID', 'WINNING', 'RESERVE_NOT_MET', 'CANCELLED'));

ALTER TABLE sale_events ADD CONSTRAINT chk_sale_event_auction_close_reason
    CHECK (close_reason IS NULL OR close_reason IN ('SOLD', 'NO_BIDS', 'RESERVE_NOT_MET', 'INVENTORY_UNAVAILABLE'));

ALTER TABLE sale_events ADD CONSTRAINT chk_sale_event_auction_rules
    CHECK (
        sale_type <> 'AUCTION'
        OR (
            stock_quantity = 1
            AND min_bid_increment IS NOT NULL
            AND min_bid_increment > 0
            AND (reserve_price IS NULL OR reserve_price >= price)
        )
    );

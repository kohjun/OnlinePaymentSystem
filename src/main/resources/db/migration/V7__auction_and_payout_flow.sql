CREATE TABLE IF NOT EXISTS auction_bids (
    bid_id VARCHAR(255) PRIMARY KEY,
    sale_event_id VARCHAR(255) NOT NULL,
    customer_id VARCHAR(255) NOT NULL,
    bid_amount DECIMAL(19, 2) NOT NULL,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_auction_bid_event FOREIGN KEY (sale_event_id) REFERENCES sale_events(sale_event_id)
);

CREATE TABLE IF NOT EXISTS auction_settlements (
    settlement_id VARCHAR(255) PRIMARY KEY,
    sale_event_id VARCHAR(255) NOT NULL,
    winning_bid_id VARCHAR(255) NOT NULL,
    customer_id VARCHAR(255) NOT NULL,
    seller_id VARCHAR(255) NOT NULL,
    amount DECIMAL(19, 2) NOT NULL,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    paid_at TIMESTAMP,
    CONSTRAINT fk_auction_settlement_event FOREIGN KEY (sale_event_id) REFERENCES sale_events(sale_event_id),
    CONSTRAINT fk_auction_settlement_bid FOREIGN KEY (winning_bid_id) REFERENCES auction_bids(bid_id),
    CONSTRAINT fk_auction_settlement_seller FOREIGN KEY (seller_id) REFERENCES sellers(seller_id)
);

CREATE TABLE IF NOT EXISTS seller_payouts (
    payout_id VARCHAR(255) PRIMARY KEY,
    seller_id VARCHAR(255) NOT NULL,
    source_type VARCHAR(50) NOT NULL,
    source_id VARCHAR(255) NOT NULL,
    gross_amount DECIMAL(19, 2) NOT NULL,
    platform_fee DECIMAL(19, 2) NOT NULL,
    net_amount DECIMAL(19, 2) NOT NULL,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    released_at TIMESTAMP,
    CONSTRAINT fk_seller_payout_seller FOREIGN KEY (seller_id) REFERENCES sellers(seller_id)
);

CREATE INDEX IF NOT EXISTS idx_auction_bids_event_amount ON auction_bids(sale_event_id, bid_amount DESC);
CREATE INDEX IF NOT EXISTS idx_auction_bids_event_created ON auction_bids(sale_event_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_auction_settlements_event_status ON auction_settlements(sale_event_id, status);
CREATE INDEX IF NOT EXISTS idx_seller_payouts_seller_status ON seller_payouts(seller_id, status);

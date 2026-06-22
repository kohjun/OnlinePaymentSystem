CREATE TABLE IF NOT EXISTS sellers (
    seller_id VARCHAR(255) PRIMARY KEY,
    display_name VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL,
    verification_status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS marketplace_listings (
    listing_id VARCHAR(255) PRIMARY KEY,
    seller_id VARCHAR(255) NOT NULL,
    product_id VARCHAR(255) NOT NULL,
    title VARCHAR(255) NOT NULL,
    description VARCHAR(1000),
    image_url VARCHAR(1000),
    item_condition VARCHAR(100),
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    CONSTRAINT fk_marketplace_listing_seller FOREIGN KEY (seller_id) REFERENCES sellers(seller_id),
    CONSTRAINT fk_marketplace_listing_product FOREIGN KEY (product_id) REFERENCES products(id)
);

CREATE TABLE IF NOT EXISTS sale_events (
    sale_event_id VARCHAR(255) PRIMARY KEY,
    listing_id VARCHAR(255) NOT NULL,
    seller_id VARCHAR(255) NOT NULL,
    product_id VARCHAR(255) NOT NULL,
    sale_type VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL,
    starts_at TIMESTAMP NOT NULL,
    ends_at TIMESTAMP,
    price DECIMAL(19, 2) NOT NULL,
    stock_quantity INT NOT NULL,
    min_bid_increment DECIMAL(19, 2),
    reserve_price DECIMAL(19, 2),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    CONSTRAINT fk_sale_event_listing FOREIGN KEY (listing_id) REFERENCES marketplace_listings(listing_id),
    CONSTRAINT fk_sale_event_seller FOREIGN KEY (seller_id) REFERENCES sellers(seller_id),
    CONSTRAINT fk_sale_event_product FOREIGN KEY (product_id) REFERENCES products(id)
);

CREATE INDEX IF NOT EXISTS idx_marketplace_listings_seller ON marketplace_listings(seller_id);
CREATE INDEX IF NOT EXISTS idx_marketplace_listings_product ON marketplace_listings(product_id);
CREATE INDEX IF NOT EXISTS idx_marketplace_listings_status ON marketplace_listings(status);
CREATE INDEX IF NOT EXISTS idx_sale_events_status_type ON sale_events(status, sale_type);
CREATE INDEX IF NOT EXISTS idx_sale_events_listing ON sale_events(listing_id);
CREATE INDEX IF NOT EXISTS idx_sale_events_product ON sale_events(product_id);
CREATE INDEX IF NOT EXISTS idx_sale_events_schedule ON sale_events(starts_at, ends_at);

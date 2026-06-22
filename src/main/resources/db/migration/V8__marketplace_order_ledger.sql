CREATE TABLE marketplace_orders (
    marketplace_order_id VARCHAR(64) PRIMARY KEY,
    sale_event_id VARCHAR(64) NOT NULL,
    listing_id VARCHAR(64) NOT NULL,
    seller_id VARCHAR(64) NOT NULL,
    customer_id VARCHAR(128) NOT NULL,
    sale_type VARCHAR(32) NOT NULL,
    checkout_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    fulfillment_status VARCHAR(32) NOT NULL,
    product_id VARCHAR(64) NOT NULL,
    quantity INTEGER NOT NULL,
    amount NUMERIC(19, 2) NOT NULL,
    currency VARCHAR(16) NOT NULL,
    reservation_id VARCHAR(64),
    order_id VARCHAR(64),
    payment_id VARCHAR(64),
    workflow_id VARCHAR(128),
    source_id VARCHAR(64),
    created_at TIMESTAMP NOT NULL,
    paid_at TIMESTAMP,
    updated_at TIMESTAMP,
    fulfilled_at TIMESTAMP,
    CONSTRAINT fk_marketplace_orders_sale_event
        FOREIGN KEY (sale_event_id) REFERENCES sale_events(sale_event_id),
    CONSTRAINT fk_marketplace_orders_listing
        FOREIGN KEY (listing_id) REFERENCES marketplace_listings(listing_id),
    CONSTRAINT fk_marketplace_orders_seller
        FOREIGN KEY (seller_id) REFERENCES sellers(seller_id)
);

CREATE INDEX idx_marketplace_orders_customer_created
    ON marketplace_orders(customer_id, created_at DESC);
CREATE INDEX idx_marketplace_orders_seller_created
    ON marketplace_orders(seller_id, created_at DESC);
CREATE INDEX idx_marketplace_orders_event
    ON marketplace_orders(sale_event_id);
CREATE UNIQUE INDEX ux_marketplace_orders_order_id
    ON marketplace_orders(order_id);

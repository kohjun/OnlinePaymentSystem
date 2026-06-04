CREATE SEQUENCE IF NOT EXISTS wal_lsn_sequence START WITH 1000 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS products (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(255),
    price DECIMAL(10, 2) NOT NULL,
    category VARCHAR(255),
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS inventory (
    product_id VARCHAR(255) PRIMARY KEY,
    total_quantity INT NOT NULL,
    available_quantity INT NOT NULL,
    reserved_quantity INT NOT NULL,
    version BIGINT,
    last_updated_at TIMESTAMP,
    CONSTRAINT fk_inventory_product_id FOREIGN KEY (product_id) REFERENCES products(id)
);

CREATE TABLE IF NOT EXISTS reservations (
    id VARCHAR(255) PRIMARY KEY,
    product_id VARCHAR(255) NOT NULL,
    order_id VARCHAR(255),
    payment_id VARCHAR(255),
    quantity INT NOT NULL,
    status VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS inventory_reservations (
    reservation_id VARCHAR(255) PRIMARY KEY,
    product_id VARCHAR(255) NOT NULL,
    customer_id VARCHAR(255) NOT NULL,
    order_id VARCHAR(255),
    payment_id VARCHAR(255),
    quantity INT NOT NULL,
    status VARCHAR(50) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS orders (
    order_id VARCHAR(255) PRIMARY KEY,
    customer_id VARCHAR(255) NOT NULL,
    product_id VARCHAR(255) NOT NULL,
    reservation_id VARCHAR(255) NOT NULL,
    payment_id VARCHAR(255),
    quantity INT NOT NULL,
    amount DECIMAL(19, 2) NOT NULL,
    currency VARCHAR(10) NOT NULL,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS payments (
    payment_id VARCHAR(255) PRIMARY KEY,
    order_id VARCHAR(255) NOT NULL,
    reservation_id VARCHAR(255) NOT NULL,
    customer_id VARCHAR(255) NOT NULL,
    amount DECIMAL(19, 2) NOT NULL,
    currency VARCHAR(10) NOT NULL,
    method VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL,
    transaction_id VARCHAR(255),
    approval_number VARCHAR(255),
    gateway_name VARCHAR(255),
    failure_reason TEXT,
    processed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS inventory_transactions (
    id VARCHAR(255) PRIMARY KEY,
    product_id VARCHAR(255) NOT NULL,
    transaction_type VARCHAR(255) NOT NULL,
    quantity_change INT NOT NULL,
    previous_available INT NOT NULL,
    new_available INT NOT NULL,
    previous_reserved INT NOT NULL,
    new_reserved INT NOT NULL,
    reservation_id VARCHAR(255),
    order_id VARCHAR(255),
    payment_id VARCHAR(255),
    reason VARCHAR(255),
    created_at TIMESTAMP,
    created_by VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS outbox_events (
    event_id VARCHAR(255) PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    topic VARCHAR(255) NOT NULL,
    event_key VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(50) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMP NOT NULL,
    published_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS wal_logs (
    log_id VARCHAR(255) PRIMARY KEY,
    lsn BIGINT NOT NULL UNIQUE,
    transaction_id VARCHAR(255) NOT NULL,
    operation VARCHAR(100) NOT NULL,
    table_name VARCHAR(100) NOT NULL,
    before_data TEXT,
    after_data TEXT,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    message TEXT,
    related_log_id VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    written_at TIMESTAMP,
    updated_at TIMESTAMP,
    completed_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_inventory_reservation_product_status ON inventory_reservations(product_id, status);
CREATE INDEX IF NOT EXISTS idx_orders_reservation ON orders(reservation_id);
CREATE INDEX IF NOT EXISTS idx_payments_reservation ON payments(reservation_id);
CREATE INDEX IF NOT EXISTS idx_outbox_status_created ON outbox_events(status, created_at);
CREATE INDEX IF NOT EXISTS idx_wal_lsn ON wal_logs(lsn);
CREATE INDEX IF NOT EXISTS idx_wal_transaction ON wal_logs(transaction_id);
CREATE INDEX IF NOT EXISTS idx_wal_status ON wal_logs(status);
CREATE INDEX IF NOT EXISTS idx_wal_created ON wal_logs(created_at);

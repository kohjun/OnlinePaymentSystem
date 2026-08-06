CREATE TABLE IF NOT EXISTS marketplace_order_cancellations (
    cancellation_id VARCHAR(255) PRIMARY KEY,
    marketplace_order_id VARCHAR(255) NOT NULL UNIQUE,
    payment_id VARCHAR(255) NOT NULL,
    refund_idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    requested_by_type VARCHAR(20) NOT NULL,
    requested_by_id VARCHAR(255) NOT NULL,
    reason VARCHAR(1000),
    status VARCHAR(40) NOT NULL,
    refund_id VARCHAR(255),
    failure_reason VARCHAR(2000),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    completed_at TIMESTAMP,
    CONSTRAINT fk_marketplace_cancellation_order
        FOREIGN KEY (marketplace_order_id) REFERENCES marketplace_orders(marketplace_order_id),
    CONSTRAINT chk_marketplace_cancellation_status CHECK (
        status IN ('REQUESTED', 'REFUND_PROCESSING', 'COMPLETED', 'REFUND_UNKNOWN', 'REFUND_FAILED')
    )
);

CREATE TABLE IF NOT EXISTS inventory_movements (
    movement_id VARCHAR(255) PRIMARY KEY,
    operation_key VARCHAR(255) NOT NULL UNIQUE,
    marketplace_order_id VARCHAR(255) NOT NULL,
    reservation_id VARCHAR(255),
    product_id VARCHAR(255) NOT NULL,
    quantity INT NOT NULL,
    type VARCHAR(40) NOT NULL,
    status VARCHAR(40) NOT NULL,
    failure_reason VARCHAR(2000),
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_inventory_movement_order
        FOREIGN KEY (marketplace_order_id) REFERENCES marketplace_orders(marketplace_order_id),
    CONSTRAINT fk_inventory_movement_product
        FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT chk_inventory_movement_quantity CHECK (quantity > 0),
    CONSTRAINT chk_inventory_movement_type CHECK (type IN ('ORDER_CANCELLATION_RESTORE')),
    CONSTRAINT chk_inventory_movement_status CHECK (status IN ('APPLIED', 'SYNC_REQUIRED'))
);

CREATE INDEX IF NOT EXISTS idx_marketplace_cancellations_status
    ON marketplace_order_cancellations(status, updated_at);
CREATE INDEX IF NOT EXISTS idx_inventory_movements_order
    ON inventory_movements(marketplace_order_id, created_at);

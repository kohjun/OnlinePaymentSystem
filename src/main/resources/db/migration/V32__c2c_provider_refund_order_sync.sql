UPDATE marketplace_orders SET status = 'FAILED'
WHERE status NOT IN ('PENDING', 'PAID', 'FAILED', 'CANCELLED', 'REFUNDED', 'PARTIALLY_REFUNDED', 'REFUND_FAILED');

ALTER TABLE marketplace_orders DROP CONSTRAINT IF EXISTS chk_marketplace_orders_status;

ALTER TABLE marketplace_orders
    ADD CONSTRAINT chk_marketplace_orders_status
    CHECK (status IN ('PENDING', 'PAID', 'FAILED', 'CANCELLED', 'REFUNDED', 'PARTIALLY_REFUNDED', 'REFUND_FAILED'));

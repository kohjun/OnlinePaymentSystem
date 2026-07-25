UPDATE marketplace_orders SET quantity = 1 WHERE quantity <= 0;
UPDATE marketplace_orders SET amount = 0 WHERE amount < 0;
UPDATE marketplace_orders SET currency = 'KRW' WHERE currency IS NULL OR LENGTH(currency) <> 3;
UPDATE marketplace_orders SET status = 'FAILED'
WHERE status NOT IN ('PENDING', 'PAID', 'FAILED', 'CANCELLED', 'REFUNDED', 'REFUND_FAILED');
UPDATE marketplace_orders SET fulfillment_status = 'NOT_READY'
WHERE fulfillment_status NOT IN ('NOT_READY', 'READY_TO_FULFILL', 'PROCESSING', 'SHIPPED', 'DELIVERED', 'CANCELLED');
UPDATE marketplace_orders SET dispute_resolution = NULL
WHERE dispute_resolution IS NOT NULL
  AND dispute_resolution NOT IN ('PAYOUT_READY', 'PAYOUT_CANCELLED', 'BUYER_REFUND');

ALTER TABLE marketplace_orders
    ADD CONSTRAINT chk_marketplace_orders_quantity_positive CHECK (quantity > 0);

ALTER TABLE marketplace_orders
    ADD CONSTRAINT chk_marketplace_orders_amount_non_negative CHECK (amount >= 0);

ALTER TABLE marketplace_orders
    ADD CONSTRAINT chk_marketplace_orders_currency_length CHECK (LENGTH(currency) = 3);

ALTER TABLE marketplace_orders
    ADD CONSTRAINT chk_marketplace_orders_status
    CHECK (status IN ('PENDING', 'PAID', 'FAILED', 'CANCELLED', 'REFUNDED', 'REFUND_FAILED'));

ALTER TABLE marketplace_orders
    ADD CONSTRAINT chk_marketplace_orders_fulfillment_status
    CHECK (fulfillment_status IN ('NOT_READY', 'READY_TO_FULFILL', 'PROCESSING', 'SHIPPED', 'DELIVERED', 'CANCELLED'));

ALTER TABLE marketplace_orders
    ADD CONSTRAINT chk_marketplace_orders_dispute_resolution
    CHECK (dispute_resolution IS NULL OR dispute_resolution IN ('PAYOUT_READY', 'PAYOUT_CANCELLED', 'BUYER_REFUND'));

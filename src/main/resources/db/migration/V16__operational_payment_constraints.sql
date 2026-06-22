UPDATE products SET price = 0 WHERE price < 0;
UPDATE inventory SET total_quantity = 0 WHERE total_quantity < 0;
UPDATE inventory SET available_quantity = 0 WHERE available_quantity < 0;
UPDATE inventory SET reserved_quantity = 0 WHERE reserved_quantity < 0;
UPDATE inventory_reservations SET quantity = 1 WHERE quantity <= 0;
UPDATE orders SET quantity = 1 WHERE quantity <= 0;
UPDATE orders SET amount = 0 WHERE amount < 0;
UPDATE payments SET amount = 0 WHERE amount < 0;
UPDATE refunds SET amount = 0 WHERE amount < 0;
UPDATE toss_payment_intents SET quantity = 1 WHERE quantity <= 0;
UPDATE toss_payment_intents SET amount = 0 WHERE amount < 0;

ALTER TABLE products
    ADD CONSTRAINT chk_products_price_non_negative CHECK (price >= 0);

ALTER TABLE inventory
    ADD CONSTRAINT chk_inventory_quantities_non_negative
    CHECK (total_quantity >= 0 AND available_quantity >= 0 AND reserved_quantity >= 0);

ALTER TABLE inventory_reservations
    ADD CONSTRAINT chk_inventory_reservations_quantity_positive CHECK (quantity > 0);

ALTER TABLE inventory_reservations
    ADD CONSTRAINT chk_inventory_reservations_status
    CHECK (status IN ('RESERVED', 'CONFIRMED', 'CANCELLED', 'CANCELED', 'EXPIRED', 'RELEASED'));

ALTER TABLE orders
    ADD CONSTRAINT chk_orders_quantity_positive CHECK (quantity > 0);

ALTER TABLE orders
    ADD CONSTRAINT chk_orders_amount_non_negative CHECK (amount >= 0);

ALTER TABLE orders
    ADD CONSTRAINT chk_orders_currency_length CHECK (LENGTH(currency) = 3);

ALTER TABLE orders
    ADD CONSTRAINT chk_orders_status
    CHECK (status IN ('CREATED', 'PAID', 'CANCELLED', 'CANCELED', 'FAILED', 'REFUNDED', 'PARTIALLY_REFUNDED'));

ALTER TABLE payments
    ADD CONSTRAINT chk_payments_amount_non_negative CHECK (amount >= 0);

ALTER TABLE payments
    ADD CONSTRAINT chk_payments_currency_length CHECK (LENGTH(currency) = 3);

ALTER TABLE payments
    ADD CONSTRAINT chk_payments_status
    CHECK (status IN ('CREATED', 'PROCESSING', 'APPROVED', 'COMPLETED', 'FAILED', 'UNKNOWN', 'REFUNDED', 'PARTIALLY_REFUNDED', 'REFUND_FAILED', 'CANCELLED', 'CANCELED'));

ALTER TABLE refunds
    ADD CONSTRAINT chk_refunds_amount_non_negative CHECK (amount >= 0);

ALTER TABLE refunds
    ADD CONSTRAINT chk_refunds_currency_length CHECK (LENGTH(currency) = 3);

ALTER TABLE refunds
    ADD CONSTRAINT chk_refunds_status
    CHECK (status IN ('PROCESSING', 'SUCCEEDED', 'FAILED'));

ALTER TABLE toss_payment_intents
    ADD CONSTRAINT chk_toss_payment_intents_quantity_positive CHECK (quantity > 0);

ALTER TABLE toss_payment_intents
    ADD CONSTRAINT chk_toss_payment_intents_amount_non_negative CHECK (amount >= 0);

ALTER TABLE toss_payment_intents
    ADD CONSTRAINT chk_toss_payment_intents_currency_length CHECK (LENGTH(currency) = 3);

ALTER TABLE toss_payment_intents
    ADD CONSTRAINT chk_toss_payment_intents_status
    CHECK (status IN ('READY', 'AUTHENTICATED', 'PENDING', 'SUCCESS', 'FAILED', 'CANCELLED', 'CANCELED', 'EXPIRED', 'UNKNOWN'));

ALTER TABLE inventory_reservations
    ADD CONSTRAINT fk_inventory_reservations_product_id
    FOREIGN KEY (product_id) REFERENCES products(id);

ALTER TABLE orders
    ADD CONSTRAINT fk_orders_product_id
    FOREIGN KEY (product_id) REFERENCES products(id);

ALTER TABLE orders
    ADD CONSTRAINT fk_orders_reservation_id
    FOREIGN KEY (reservation_id) REFERENCES inventory_reservations(reservation_id);

ALTER TABLE payments
    ADD CONSTRAINT fk_payments_order_id
    FOREIGN KEY (order_id) REFERENCES orders(order_id);

ALTER TABLE payments
    ADD CONSTRAINT fk_payments_reservation_id
    FOREIGN KEY (reservation_id) REFERENCES inventory_reservations(reservation_id);

ALTER TABLE refunds
    ADD CONSTRAINT fk_refunds_payment_id
    FOREIGN KEY (payment_id) REFERENCES payments(payment_id);

CREATE INDEX IF NOT EXISTS ix_toss_payment_intents_payment_key
    ON toss_payment_intents(payment_key);

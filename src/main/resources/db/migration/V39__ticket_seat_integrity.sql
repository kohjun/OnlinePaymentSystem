ALTER TABLE marketplace_orders
    ADD COLUMN IF NOT EXISTS seat_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS ix_marketplace_orders_seat
    ON marketplace_orders (sale_event_id, seat_id);

CREATE INDEX IF NOT EXISTS ix_toss_payment_intents_ticket_seat
    ON toss_payment_intents (sale_event_id, seat_id, status);

ALTER TABLE inventory_reservations
    ADD COLUMN IF NOT EXISTS active_seat_id VARCHAR(50);

UPDATE inventory_reservations
   SET active_seat_id = CASE
       WHEN status IN ('RESERVED', 'CONFIRMED') THEN seat_id
       ELSE NULL
   END;

CREATE UNIQUE INDEX IF NOT EXISTS ux_inventory_reservations_active_seat
    ON inventory_reservations (active_seat_id);

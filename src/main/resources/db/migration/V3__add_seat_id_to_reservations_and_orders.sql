-- V3__add_seat_id_to_reservations_and_orders.sql
ALTER TABLE inventory_reservations ADD COLUMN IF NOT EXISTS seat_id VARCHAR(50);
ALTER TABLE reservations ADD COLUMN IF NOT EXISTS seat_id VARCHAR(50);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS seat_id VARCHAR(50);

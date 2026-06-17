-- V4__add_index_on_seat_id.sql
CREATE INDEX IF NOT EXISTS idx_inventory_reservations_seat_status ON inventory_reservations(seat_id, status);

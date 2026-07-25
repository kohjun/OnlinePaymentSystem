ALTER TABLE raffle_winners ADD COLUMN IF NOT EXISTS draw_seed_commitment VARCHAR(64);
ALTER TABLE raffle_winners ADD COLUMN IF NOT EXISTS entry_snapshot_hash VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_raffle_winners_draw_audit
    ON raffle_winners(sale_event_id, draw_seed_commitment);

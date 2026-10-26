ALTER TABLE sale_events ADD COLUMN IF NOT EXISTS activation_mode VARCHAR(20);
ALTER TABLE sale_events ADD COLUMN IF NOT EXISTS activation_failure_reason VARCHAR(1000);
ALTER TABLE sale_events ADD COLUMN IF NOT EXISTS active_schedule_slot VARCHAR(255);

UPDATE sale_events
SET activation_mode = CASE
    WHEN status = 'SCHEDULED' THEN 'SCHEDULED'
    ELSE 'IMMEDIATE'
END
WHERE activation_mode IS NULL;

ALTER TABLE sale_events ALTER COLUMN activation_mode SET NOT NULL;

UPDATE sale_events
SET ends_at = starts_at + INTERVAL '1' DAY
WHERE sale_type IN ('AUCTION', 'RAFFLE', 'DROP')
  AND (ends_at IS NULL OR ends_at <= starts_at);

UPDATE sale_events
SET status = 'CANCELLED',
    activation_failure_reason = 'Superseded by another active schedule during migration.'
WHERE status IN ('SCHEDULED', 'LIVE')
  AND sale_event_id NOT IN (
      SELECT MAX(sale_event_id)
      FROM sale_events
      WHERE status IN ('SCHEDULED', 'LIVE')
      GROUP BY listing_id
  );

UPDATE sale_events
SET active_schedule_slot = CASE
    WHEN status IN ('SCHEDULED', 'LIVE') THEN listing_id
    ELSE NULL
END;

ALTER TABLE sale_events DROP CONSTRAINT IF EXISTS chk_sale_event_realtime_status;
ALTER TABLE sale_events ADD CONSTRAINT chk_sale_event_realtime_status CHECK (
    status IN ('SCHEDULED', 'LIVE', 'ACTIVATION_FAILED', 'ENDED', 'CANCELLED')
);
ALTER TABLE sale_events ADD CONSTRAINT chk_sale_event_activation_mode CHECK (
    activation_mode IN ('IMMEDIATE', 'SCHEDULED')
);
ALTER TABLE sale_events ADD CONSTRAINT chk_sale_event_schedule_window CHECK (
    (sale_type = 'FIXED_PRICE' AND (ends_at IS NULL OR ends_at > starts_at))
    OR (sale_type IN ('AUCTION', 'RAFFLE', 'DROP') AND ends_at IS NOT NULL AND ends_at > starts_at)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_sale_event_active_schedule_slot
    ON sale_events(active_schedule_slot);
CREATE INDEX IF NOT EXISTS idx_sale_event_activation_due
    ON sale_events(status, starts_at);

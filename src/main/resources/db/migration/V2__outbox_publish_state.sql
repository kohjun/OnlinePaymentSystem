ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMP;
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS locked_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_outbox_status_next_attempt ON outbox_events(status, next_attempt_at, created_at);

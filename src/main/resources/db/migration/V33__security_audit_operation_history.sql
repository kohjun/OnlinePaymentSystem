ALTER TABLE security_audit_events
    ADD COLUMN IF NOT EXISTS ip_address VARCHAR(100);

ALTER TABLE security_audit_events
    ADD COLUMN IF NOT EXISTS user_agent TEXT;

CREATE INDEX IF NOT EXISTS ix_security_audit_events_action_created
    ON security_audit_events(action, created_at);

CREATE INDEX IF NOT EXISTS ix_security_audit_events_outcome_created
    ON security_audit_events(outcome, created_at);

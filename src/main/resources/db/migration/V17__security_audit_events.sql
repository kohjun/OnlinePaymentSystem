CREATE TABLE IF NOT EXISTS security_audit_events (
    event_id VARCHAR(80) PRIMARY KEY,
    actor_id VARCHAR(200),
    actor_roles TEXT,
    action VARCHAR(100) NOT NULL,
    resource_type VARCHAR(100) NOT NULL,
    resource_id VARCHAR(200),
    outcome VARCHAR(50) NOT NULL,
    reason TEXT,
    ip_address VARCHAR(100),
    user_agent TEXT,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_security_audit_events_created_at
    ON security_audit_events(created_at);

CREATE INDEX IF NOT EXISTS ix_security_audit_events_resource
    ON security_audit_events(resource_type, resource_id, created_at);

CREATE INDEX IF NOT EXISTS ix_security_audit_events_actor
    ON security_audit_events(actor_id, created_at);
-- Existing holds have no proof of the authorization boundary; never infer it from a missing payment.
ALTER TABLE inventory_reservations ADD COLUMN authorization_state VARCHAR(32) NOT NULL DEFAULT 'UNTRACKED';
ALTER TABLE inventory_reservations ADD COLUMN authorization_started_at TIMESTAMP;
ALTER TABLE inventory_reservations ADD COLUMN authorization_closed_at TIMESTAMP;
ALTER TABLE inventory_reservations ADD COLUMN authorization_close_workflow_id VARCHAR(255);
ALTER TABLE inventory_reservations ADD CONSTRAINT ck_reservation_authorization_state CHECK (
    (authorization_state IN ('UNTRACKED', 'NOT_STARTED') AND authorization_started_at IS NULL
        AND authorization_closed_at IS NULL AND authorization_close_workflow_id IS NULL)
    OR (authorization_state = 'STARTED' AND authorization_started_at IS NOT NULL
        AND authorization_closed_at IS NULL AND authorization_close_workflow_id IS NULL)
    OR (authorization_state = 'CLOSED_WITHOUT_AUTHORIZATION' AND authorization_started_at IS NULL
        AND authorization_closed_at IS NOT NULL AND authorization_close_workflow_id IS NOT NULL)
);

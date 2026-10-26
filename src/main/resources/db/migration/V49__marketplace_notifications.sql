CREATE TABLE marketplace_notifications (
    notification_id VARCHAR(64) PRIMARY KEY,
    recipient_user_id VARCHAR(255),
    recipient_customer_id VARCHAR(255),
    recipient_seller_id VARCHAR(255),
    notification_type VARCHAR(60) NOT NULL,
    title VARCHAR(255) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    action_path VARCHAR(1000),
    aggregate_type VARCHAR(60),
    aggregate_id VARCHAR(255),
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    read_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT chk_marketplace_notification_recipient CHECK (
        recipient_user_id IS NOT NULL OR recipient_customer_id IS NOT NULL OR recipient_seller_id IS NOT NULL
    )
);

CREATE INDEX idx_marketplace_notifications_user
    ON marketplace_notifications(recipient_user_id, read_at, created_at DESC);
CREATE INDEX idx_marketplace_notifications_customer
    ON marketplace_notifications(recipient_customer_id, read_at, created_at DESC);
CREATE INDEX idx_marketplace_notifications_seller
    ON marketplace_notifications(recipient_seller_id, read_at, created_at DESC);

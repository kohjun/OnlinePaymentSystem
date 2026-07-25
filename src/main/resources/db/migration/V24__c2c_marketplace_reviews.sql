CREATE TABLE IF NOT EXISTS marketplace_reviews (
    review_id VARCHAR(64) PRIMARY KEY,
    marketplace_order_id VARCHAR(100) NOT NULL,
    reviewer_user_id VARCHAR(100) NOT NULL,
    reviewer_customer_id VARCHAR(100) NOT NULL,
    target_seller_id VARCHAR(100) NOT NULL,
    rating INTEGER NOT NULL,
    comment VARCHAR(2000),
    status VARCHAR(40) NOT NULL DEFAULT 'VISIBLE',
    moderated_by VARCHAR(100),
    moderation_note VARCHAR(2000),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    CONSTRAINT uk_marketplace_reviews_order_reviewer UNIQUE (marketplace_order_id, reviewer_user_id),
    CONSTRAINT ck_marketplace_reviews_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT ck_marketplace_reviews_status CHECK (status IN ('VISIBLE', 'HIDDEN', 'REMOVED'))
);

CREATE INDEX IF NOT EXISTS ix_marketplace_reviews_seller_visible
    ON marketplace_reviews (target_seller_id, status, created_at DESC);

CREATE INDEX IF NOT EXISTS ix_marketplace_reviews_reviewer
    ON marketplace_reviews (reviewer_user_id, created_at DESC);

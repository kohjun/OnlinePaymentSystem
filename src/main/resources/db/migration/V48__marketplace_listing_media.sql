CREATE TABLE marketplace_listing_media (
    media_id VARCHAR(64) PRIMARY KEY,
    listing_id VARCHAR(255) NOT NULL,
    media_type VARCHAR(40) NOT NULL,
    storage_key VARCHAR(1000) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    sort_order INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_listing_media_listing
        FOREIGN KEY (listing_id) REFERENCES marketplace_listings(listing_id) ON DELETE CASCADE,
    CONSTRAINT uq_listing_media_storage_key UNIQUE (storage_key),
    CONSTRAINT uq_listing_media_position UNIQUE (listing_id, media_type, sort_order),
    CONSTRAINT chk_listing_media_type
        CHECK (media_type IN ('PRODUCT_IMAGE', 'AUTHENTICITY_EVIDENCE')),
    CONSTRAINT chk_listing_media_size CHECK (size_bytes > 0),
    CONSTRAINT chk_listing_media_sort_order CHECK (sort_order BETWEEN 0 AND 2)
);

CREATE INDEX idx_listing_media_listing_type
    ON marketplace_listing_media(listing_id, media_type, sort_order);

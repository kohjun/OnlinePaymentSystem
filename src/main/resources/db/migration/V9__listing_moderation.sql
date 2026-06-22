ALTER TABLE marketplace_listings
    ADD COLUMN reviewed_by VARCHAR(128);

ALTER TABLE marketplace_listings
    ADD COLUMN reviewed_at TIMESTAMP;

ALTER TABLE marketplace_listings
    ADD COLUMN review_note VARCHAR(1000);

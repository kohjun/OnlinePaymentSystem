ALTER TABLE marketplace_listings
    ADD COLUMN IF NOT EXISTS brand VARCHAR(255);

ALTER TABLE marketplace_listings
    ADD COLUMN IF NOT EXISTS tags VARCHAR(1000);

ALTER TABLE marketplace_listings
    ADD COLUMN IF NOT EXISTS authenticity_note VARCHAR(1000);

ALTER TABLE marketplace_listings
    ADD COLUMN IF NOT EXISTS defect_description VARCHAR(1000);

CREATE INDEX IF NOT EXISTS ix_marketplace_listings_brand
    ON marketplace_listings(brand);

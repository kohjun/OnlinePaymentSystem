ALTER TABLE sellers ADD COLUMN IF NOT EXISTS verification_evidence_ref VARCHAR(500);
ALTER TABLE sellers ADD COLUMN IF NOT EXISTS verification_note VARCHAR(1000);
ALTER TABLE sellers ADD COLUMN IF NOT EXISTS verification_submitted_at TIMESTAMP;
ALTER TABLE sellers ADD COLUMN IF NOT EXISTS verification_reviewed_by VARCHAR(100);
ALTER TABLE sellers ADD COLUMN IF NOT EXISTS verification_reviewed_at TIMESTAMP;

UPDATE sellers SET verification_status = 'UNVERIFIED'
WHERE verification_status NOT IN ('UNVERIFIED', 'PENDING_REVIEW', 'VERIFIED', 'REJECTED');

CREATE INDEX IF NOT EXISTS ix_sellers_verification_status
    ON sellers (verification_status, verification_submitted_at);

ALTER TABLE sellers ADD CONSTRAINT chk_sellers_status
    CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED'));

ALTER TABLE sellers ADD CONSTRAINT chk_sellers_verification_status
    CHECK (verification_status IN ('UNVERIFIED', 'PENDING_REVIEW', 'VERIFIED', 'REJECTED'));

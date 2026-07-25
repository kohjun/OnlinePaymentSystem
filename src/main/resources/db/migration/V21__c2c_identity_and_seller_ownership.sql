CREATE TABLE IF NOT EXISTS users (
    user_id VARCHAR(255) PRIMARY KEY,
    customer_id VARCHAR(255) NOT NULL UNIQUE,
    email VARCHAR(255),
    display_name VARCHAR(255),
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    last_seen_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS buyer_profiles (
    user_id VARCHAR(255) PRIMARY KEY,
    customer_id VARCHAR(255) NOT NULL UNIQUE,
    display_name VARCHAR(255),
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    CONSTRAINT fk_buyer_profile_user FOREIGN KEY (user_id) REFERENCES users(user_id)
);

ALTER TABLE sellers
    ADD COLUMN IF NOT EXISTS owner_user_id VARCHAR(255);

ALTER TABLE sellers
    ADD COLUMN IF NOT EXISTS owner_customer_id VARCHAR(255);

CREATE UNIQUE INDEX IF NOT EXISTS ux_sellers_owner_user_id
    ON sellers(owner_user_id);

CREATE INDEX IF NOT EXISTS ix_sellers_owner_customer_id
    ON sellers(owner_customer_id);

CREATE INDEX IF NOT EXISTS ix_users_customer_id
    ON users(customer_id);

ALTER TABLE users ADD CONSTRAINT chk_users_status
    CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED'));

ALTER TABLE buyer_profiles ADD CONSTRAINT chk_buyer_profiles_status
    CHECK (status IN ('ACTIVE', 'RESTRICTED', 'SUSPENDED'));

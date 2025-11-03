-- H2 DB 호환 스키마 (모든 엔티티 포함)

-- 0. 시퀀스
CREATE SEQUENCE IF NOT EXISTS wal_lsn_sequence START WITH 1000 INCREMENT BY 1;

-- 1. wal_logs (WalLogEntry.java)
CREATE TABLE IF NOT EXISTS wal_logs (
                                        log_id VARCHAR(255) PRIMARY KEY,
    lsn BIGINT NOT NULL UNIQUE,
    transaction_id VARCHAR(255) NOT NULL,
    operation VARCHAR(100) NOT NULL,
    table_name VARCHAR(100) NOT NULL,
    before_data TEXT,
    after_data TEXT,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    message TEXT,
    related_log_id VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    written_at TIMESTAMP,
    updated_at TIMESTAMP,
    completed_at TIMESTAMP
    );

-- wal_logs 인덱스
CREATE INDEX IF NOT EXISTS idx_wal_lsn ON wal_logs(lsn);
CREATE INDEX IF NOT EXISTS idx_wal_transaction ON wal_logs(transaction_id);
CREATE INDEX IF NOT EXISTS idx_wal_status ON wal_logs(status);
CREATE INDEX IF NOT EXISTS idx_wal_created ON wal_logs(created_at);


-- 2. products (Product.java)
CREATE TABLE IF NOT EXISTS products (
                                        id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(255),
    price DECIMAL(10, 2) NOT NULL,
    category VARCHAR(255),
    created_at TIMESTAMP,
    updated_at TIMESTAMP
    );

-- 3. inventory (Inventory.java)
CREATE TABLE IF NOT EXISTS inventory (
                                         product_id VARCHAR(255) PRIMARY KEY,
    total_quantity INT NOT NULL,
    available_quantity INT NOT NULL,
    reserved_quantity INT NOT NULL,
    version BIGINT,
    last_updated_at TIMESTAMP
    );

-- 4. reservations (Reservation.java)
CREATE TABLE IF NOT EXISTS reservations (
                                            id VARCHAR(255) PRIMARY KEY,
    product_id VARCHAR(255) NOT NULL,
    order_id VARCHAR(255),
    payment_id VARCHAR(255),
    quantity INT NOT NULL,
    status VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
    );

-- 5. inventory_transactions (InventoryTransaction.java)
CREATE TABLE IF NOT EXISTS inventory_transactions (
                                                      id VARCHAR(255) PRIMARY KEY,
    product_id VARCHAR(255) NOT NULL,
    transaction_type VARCHAR(255) NOT NULL,
    quantity_change INT NOT NULL,
    previous_available INT NOT NULL,
    new_available INT NOT NULL,
    previous_reserved INT NOT NULL,
    new_reserved INT NOT NULL,
    reservation_id VARCHAR(255),
    order_id VARCHAR(255),
    payment_id VARCHAR(255),
    reason VARCHAR(255),
    created_at TIMESTAMP,
    created_by VARCHAR(255)
    );

-- 6. 외래 키(Foreign Key) 제약 조건
-- Inventory(product_id) -> Products(id)
ALTER TABLE inventory
    ADD CONSTRAINT IF NOT EXISTS fk_inventory_product_id
    FOREIGN KEY (product_id)
    REFERENCES products(id);

-- (참고) 다른 엔티티들은 @ManyToOne/@OneToOne 대신 ID(String)만 저장하므로
-- JPA 레벨에서 외래 키를 요구하지 않아 'validate'를 통과합니다.
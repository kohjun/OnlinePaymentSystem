-- 상품 테이블
CREATE TABLE IF NOT EXISTS products (
                                        id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(500) NOT NULL,
    description TEXT,
    price DECIMAL(10,2) NOT NULL,
    category VARCHAR(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );

-- 재고 테이블
CREATE TABLE IF NOT EXISTS inventory (
                                         product_id VARCHAR(255) PRIMARY KEY,
    total_quantity INTEGER NOT NULL DEFAULT 0,
    available_quantity INTEGER NOT NULL DEFAULT 0,
    reserved_quantity INTEGER NOT NULL DEFAULT 0,
    version BIGINT DEFAULT 0,
    last_updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );

-- 예약 테이블
CREATE TABLE IF NOT EXISTS reservations (
                                            id VARCHAR(255) PRIMARY KEY,
    product_id VARCHAR(255) NOT NULL,
    order_id VARCHAR(255),
    payment_id VARCHAR(255),
    quantity INTEGER NOT NULL,
    status VARCHAR(50) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );

-- 재고 변경 이력 테이블
CREATE TABLE IF NOT EXISTS inventory_transactions (
                                                      id VARCHAR(255) PRIMARY KEY,
    product_id VARCHAR(255) NOT NULL,
    transaction_type VARCHAR(50) NOT NULL,
    quantity_change INTEGER NOT NULL,
    previous_available INTEGER NOT NULL,
    new_available INTEGER NOT NULL,
    previous_reserved INTEGER NOT NULL,
    new_reserved INTEGER NOT NULL,
    reservation_id VARCHAR(255),
    order_id VARCHAR(255),
    payment_id VARCHAR(255),
    reason TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100)
    );

-- ========================================
-- WAL (Write-Ahead Logging) 관련 테이블
-- ========================================

-- WAL LSN(Log Sequence Number) 시퀀스 생성
CREATE SEQUENCE IF NOT EXISTS wal_lsn_sequence START WITH 1 INCREMENT BY 1;

-- WAL 로그 테이블 (H2 데이터베이스용)
CREATE TABLE IF NOT EXISTS wal_logs (
                                        log_id VARCHAR(255) PRIMARY KEY,
    lsn BIGINT NOT NULL UNIQUE DEFAULT NEXTVAL('wal_lsn_sequence'),
    transaction_id VARCHAR(255) NOT NULL,
    operation VARCHAR(100) NOT NULL,
    table_name VARCHAR(100) NOT NULL,
    before_data TEXT,
    after_data TEXT,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    message TEXT,
    related_log_id VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    written_at TIMESTAMP NULL,
    updated_at TIMESTAMP NULL,
    completed_at TIMESTAMP NULL
    );

-- WAL 로그 인덱스들
CREATE INDEX IF NOT EXISTS idx_wal_lsn ON wal_logs(lsn);
CREATE INDEX IF NOT EXISTS idx_wal_transaction ON wal_logs(transaction_id);
CREATE INDEX IF NOT EXISTS idx_wal_status ON wal_logs(status);
CREATE INDEX IF NOT EXISTS idx_wal_created ON wal_logs(created_at);
CREATE INDEX IF NOT EXISTS idx_wal_operation ON wal_logs(operation);
CREATE INDEX IF NOT EXISTS idx_wal_table ON wal_logs(table_name);

-- 주문 테이블 (추가)
CREATE TABLE IF NOT EXISTS orders (
                                      order_id VARCHAR(255) PRIMARY KEY,
    customer_id VARCHAR(255) NOT NULL,
    product_id VARCHAR(255) NOT NULL,
    quantity INTEGER NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    currency VARCHAR(10) NOT NULL,
    payment_id VARCHAR(255),
    reservation_id VARCHAR(255),
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );

-- 결제 테이블 (추가)
CREATE TABLE IF NOT EXISTS payments (
                                        payment_id VARCHAR(255) PRIMARY KEY,
    order_id VARCHAR(255),
    reservation_id VARCHAR(255),
    customer_id VARCHAR(255) NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    currency VARCHAR(10) NOT NULL,
    method VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL,
    transaction_id VARCHAR(255),
    approval_number VARCHAR(255),
    gateway_name VARCHAR(100),
    failure_reason TEXT,
    processed_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );

-- ========================================
-- 외래키 제약조건들
-- ========================================

-- 재고-상품 외래키
ALTER TABLE inventory ADD CONSTRAINT IF NOT EXISTS fk_inventory_product
    FOREIGN KEY (product_id) REFERENCES products(id);

-- 예약-상품 외래키
ALTER TABLE reservations ADD CONSTRAINT IF NOT EXISTS fk_reservation_product
    FOREIGN KEY (product_id) REFERENCES products(id);

-- 주문-상품 외래키
ALTER TABLE orders ADD CONSTRAINT IF NOT EXISTS fk_order_product
    FOREIGN KEY (product_id) REFERENCES products(id);

-- 주문-예약 외래키 (선택적)
-- ALTER TABLE orders ADD CONSTRAINT IF NOT EXISTS fk_order_reservation
--     FOREIGN KEY (reservation_id) REFERENCES reservations(id);

-- 결제-주문 외래키 (선택적)
-- ALTER TABLE payments ADD CONSTRAINT IF NOT EXISTS fk_payment_order
--     FOREIGN KEY (order_id) REFERENCES orders(order_id);

-- ========================================
-- 초기 시퀀스 값 설정 (선택적)
-- ========================================

-- WAL LSN 시퀀스를 1000부터 시작하도록 설정 (디버깅 용이성)
ALTER SEQUENCE wal_lsn_sequence RESTART WITH 1000;
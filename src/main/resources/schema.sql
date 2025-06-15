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
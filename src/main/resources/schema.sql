-- ========================================
-- 개선된 데이터베이스 스키마
-- WAL 트랜잭션 정합성 강화 버전
-- ========================================

-- ========================================
-- WAL (Write-Ahead Logging) 시퀀스 및 테이블
-- ========================================

-- WAL LSN(Log Sequence Number) 시퀀스 생성
CREATE SEQUENCE IF NOT EXISTS wal_lsn_sequence START WITH 1000 INCREMENT BY 1;

-- ✅ 개선된 WAL 로그 테이블 (H2 데이터베이스용)
CREATE TABLE IF NOT EXISTS wal_logs (
    -- 기본 식별자
                                        log_id VARCHAR(255) PRIMARY KEY,
    lsn BIGINT NOT NULL UNIQUE DEFAULT NEXTVAL('wal_lsn_sequence'),

    -- ✅ 트랜잭션 추적 강화
    transaction_id VARCHAR(255) NOT NULL,              -- 비즈니스 트랜잭션 ID (전체 플로우 공유)

-- 작업 정보
    operation VARCHAR(100) NOT NULL,                   -- 작업 타입 (INVENTORY_RESERVE_START, etc.)
    table_name VARCHAR(100) NOT NULL,                  -- 대상 테이블

-- ✅ 데이터 추적 강화
    before_data TEXT,                                  -- 엔티티 ID JSON + 변경 전 데이터
    after_data TEXT,                                   -- 변경 후 데이터 (JSON)

-- 상태 관리
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',     -- PENDING, COMMITTED, FAILED
    message TEXT,                                      -- 상태 메시지

-- ✅ Phase 연결 강화
    related_log_id VARCHAR(255),                       -- Phase 1과 Phase 2 연결용

-- 시간 추적
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    written_at TIMESTAMP NULL,                         -- 실제 디스크 기록 시간
    updated_at TIMESTAMP NULL,
    completed_at TIMESTAMP NULL
    );

-- ========================================
-- ✅ 개선된 WAL 인덱스 (성능 및 추적 최적화)
-- ========================================

-- 기본 인덱스
CREATE INDEX IF NOT EXISTS idx_wal_lsn ON wal_logs(lsn);
CREATE INDEX IF NOT EXISTS idx_wal_status ON wal_logs(status);
CREATE INDEX IF NOT EXISTS idx_wal_operation ON wal_logs(operation);
CREATE INDEX IF NOT EXISTS idx_wal_table ON wal_logs(table_name);
CREATE INDEX IF NOT EXISTS idx_wal_created ON wal_logs(created_at);

-- ✅ 트랜잭션 추적 최적화 인덱스
CREATE INDEX IF NOT EXISTS idx_wal_transaction ON wal_logs(transaction_id);

-- ✅ Phase 연결 추적 인덱스
CREATE INDEX IF NOT EXISTS idx_wal_related ON wal_logs(related_log_id);

-- ✅ 복구용 복합 인덱스
CREATE INDEX IF NOT EXISTS idx_wal_status_created ON wal_logs(status, created_at);
CREATE INDEX IF NOT EXISTS idx_wal_transaction_lsn ON wal_logs(transaction_id, lsn);

-- ✅ 엔티티 ID 검색용 인덱스 (H2는 Full-Text 지원 제한적이므로 LIKE 검색용)
-- 프로덕션에서는 PostgreSQL의 GIN 인덱스 사용 권장:
-- CREATE INDEX idx_wal_entities ON wal_logs USING gin(to_tsvector('english', before_data));

-- ========================================
-- 주문 테이블
-- ========================================
CREATE TABLE IF NOT EXISTS orders (
                                      order_id VARCHAR(255) PRIMARY KEY,
    customer_id VARCHAR(255) NOT NULL,
    product_id VARCHAR(255) NOT NULL,
    quantity INTEGER NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    currency VARCHAR(10) NOT NULL,
    payment_id VARCHAR(255),
    reservation_id VARCHAR(255),                       -- ✅ 예약 ID 연결
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );

-- 주문 인덱스
CREATE INDEX IF NOT EXISTS idx_order_customer ON orders(customer_id);
CREATE INDEX IF NOT EXISTS idx_order_reservation ON orders(reservation_id);
CREATE INDEX IF NOT EXISTS idx_order_status ON orders(status);

-- ========================================
-- 결제 테이블
-- ========================================
CREATE TABLE IF NOT EXISTS payments (
                                        payment_id VARCHAR(255) PRIMARY KEY,
    order_id VARCHAR(255),
    reservation_id VARCHAR(255),                       -- ✅ 예약 ID 연결
    customer_id VARCHAR(255) NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    currency VARCHAR(10) NOT NULL,
    method VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL,
    transaction_id VARCHAR(255),                       -- PG 트랜잭션 ID
    approval_number VARCHAR(255),
    gateway_name VARCHAR(100),
    failure_reason TEXT,
    processed_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );

-- 결제 인덱스
CREATE INDEX IF NOT EXISTS idx_payment_order ON payments(order_id);
CREATE INDEX IF NOT EXISTS idx_payment_customer ON payments(customer_id);
CREATE INDEX IF NOT EXISTS idx_payment_status ON payments(status);

-- ========================================
-- 상품 테이블
-- ========================================
CREATE TABLE IF NOT EXISTS products (
                                        id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(500) NOT NULL,
    description TEXT,
    price DECIMAL(10,2) NOT NULL,
    category VARCHAR(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );

-- ========================================
-- 재고 테이블
-- ========================================
CREATE TABLE IF NOT EXISTS inventory (
                                         product_id VARCHAR(255) PRIMARY KEY,
    total_quantity INTEGER NOT NULL DEFAULT 0,
    available_quantity INTEGER NOT NULL DEFAULT 0,
    reserved_quantity INTEGER NOT NULL DEFAULT 0,
    version BIGINT DEFAULT 0,                          -- 낙관적 락용
    last_updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );

-- ========================================
-- 예약 테이블
-- ========================================
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

-- 예약 인덱스
CREATE INDEX IF NOT EXISTS idx_reservation_product ON reservations(product_id);
CREATE INDEX IF NOT EXISTS idx_reservation_status ON reservations(status);
CREATE INDEX IF NOT EXISTS idx_reservation_expires ON reservations(expires_at);

-- ========================================
-- 재고 변경 이력 테이블
-- ========================================
CREATE TABLE IF NOT EXISTS inventory_transactions (
                                                      id VARCHAR(255) PRIMARY KEY,
    product_id VARCHAR(255) NOT NULL,
    transaction_type VARCHAR(50) NOT NULL,             -- RESERVE, CONFIRM, CANCEL, etc.
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

-- 재고 이력 인덱스
CREATE INDEX IF NOT EXISTS idx_inv_txn_product ON inventory_transactions(product_id);
CREATE INDEX IF NOT EXISTS idx_inv_txn_reservation ON inventory_transactions(reservation_id);
CREATE INDEX IF NOT EXISTS idx_inv_txn_created ON inventory_transactions(created_at);

-- ========================================
-- 외래키 제약조건들 (선택적)
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

-- ========================================
-- ✅ WAL 추적용 뷰 (분석 및 디버깅용)
-- ========================================

-- 트랜잭션 체인 뷰
CREATE OR REPLACE VIEW v_wal_transaction_chain AS
SELECT
    t.transaction_id,
    t.log_id,
    t.lsn,
    t.operation,
    t.table_name,
    t.status,
    t.before_data,
    t.after_data,
    t.related_log_id,
    p.log_id AS related_log_id_actual,
    p.operation AS related_operation,
    t.created_at,
    t.completed_at
FROM wal_logs t
         LEFT JOIN wal_logs p ON t.related_log_id = p.log_id
ORDER BY t.transaction_id, t.lsn;

-- Phase별 로그 뷰
CREATE OR REPLACE VIEW v_wal_phase_summary AS
SELECT
    transaction_id,
    COUNT(*) AS total_logs,
    COUNT(CASE WHEN related_log_id IS NULL THEN 1 END) AS phase1_logs,
    COUNT(CASE WHEN related_log_id IS NOT NULL THEN 1 END) AS phase2_logs,
    COUNT(CASE WHEN status = 'COMMITTED' THEN 1 END) AS committed,
    COUNT(CASE WHEN status = 'FAILED' THEN 1 END) AS failed,
    COUNT(CASE WHEN status = 'PENDING' THEN 1 END) AS pending,
    MIN(created_at) AS started_at,
    MAX(completed_at) AS completed_at
FROM wal_logs
GROUP BY transaction_id;

-- 미완료 트랜잭션 뷰
CREATE OR REPLACE VIEW v_wal_incomplete_transactions AS
SELECT
    transaction_id,
    COUNT(*) AS total_logs,
    COUNT(CASE WHEN status = 'PENDING' THEN 1 END) AS pending_logs,
    COUNT(CASE WHEN status = 'FAILED' THEN 1 END) AS failed_logs,
    MIN(created_at) AS started_at,
    MAX(updated_at) AS last_updated_at
FROM wal_logs
WHERE status IN ('PENDING', 'FAILED')
GROUP BY transaction_id;

-- ========================================
-- ✅ 초기 시퀀스 값 설정
-- ========================================

-- WAL LSN 시퀀스를 1000부터 시작하도록 설정 (디버깅 용이성)
ALTER SEQUENCE wal_lsn_sequence RESTART WITH 1000;

-- ========================================
-- ✅ 설명 및 주석
-- ========================================

COMMENT ON TABLE wal_logs IS '개선된 WAL 로그 테이블 - 트랜잭션 ID 정합성 및 Phase 연결 강화';
COMMENT ON COLUMN wal_logs.transaction_id IS '비즈니스 트랜잭션 ID (전체 플로우에서 공유)';
COMMENT ON COLUMN wal_logs.related_log_id IS 'Phase 1 로그 ID (Phase 2에서 Phase 1과 연결)';
COMMENT ON COLUMN wal_logs.before_data IS '엔티티 ID JSON {"reservationId":"xxx","orderId":"yyy","paymentId":"zzz"}';

COMMENT ON INDEX idx_wal_transaction IS '트랜잭션 ID로 모든 관련 로그 조회';
COMMENT ON INDEX idx_wal_related IS 'Phase 간 로그 연결 추적';
COMMENT ON INDEX idx_wal_status_created IS '미완료 트랜잭션 복구용';

-- ========================================
-- ✅ PostgreSQL용 추가 기능 (프로덕션 환경)
-- ========================================

-- PostgreSQL에서만 사용 가능한 기능들:
/*
-- 1. JSON 컬럼 타입 사용
ALTER TABLE wal_logs
    ALTER COLUMN before_data TYPE JSONB USING before_data::JSONB,
    ALTER COLUMN after_data TYPE JSONB USING after_data::JSONB;

-- 2. JSON 인덱스 (엔티티 ID 검색 최적화)
CREATE INDEX idx_wal_entities_reservation
    ON wal_logs ((before_data->>'reservationId'));

CREATE INDEX idx_wal_entities_order
    ON wal_logs ((before_data->>'orderId'));

CREATE INDEX idx_wal_entities_payment
    ON wal_logs ((before_data->>'paymentId'));

-- 3. Full-Text 검색 인덱스
CREATE INDEX idx_wal_fulltext
    ON wal_logs USING gin(to_tsvector('english', before_data::text));

-- 4. 파티셔닝 (대용량 데이터 처리)
CREATE TABLE wal_logs_partitioned (
    LIKE wal_logs INCLUDING ALL
) PARTITION BY RANGE (created_at);

-- 월별 파티션 생성
CREATE TABLE wal_logs_2025_01 PARTITION OF wal_logs_partitioned
    FOR VALUES FROM ('2025-01-01') TO ('2025-02-01');
*/
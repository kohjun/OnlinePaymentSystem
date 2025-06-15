-- 테스트 상품 데이터
INSERT INTO products (id, name, description, price, category, created_at, updated_at) VALUES
    ('PROD-001', '테스트 스마트폰', '테스트용 스마트폰 상품', 799.99, 'ELECTRONICS', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('PROD-002', '테스트 이어버드', '테스트용 무선 이어버드', 129.99, 'ELECTRONICS', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('PROD-1001', '스마트폰', '최신 스마트폰', 799.99, 'ELECTRONICS', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('PROD-2002', '무선 이어버드', '무선 블루투스 이어버드', 129.99, 'ELECTRONICS', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 재고 데이터
INSERT INTO inventory (product_id, total_quantity, available_quantity, reserved_quantity, version, last_updated_at) VALUES
    ('PROD-001', 100, 100, 0, 0, CURRENT_TIMESTAMP),
    ('PROD-002', 50, 50, 0, 0, CURRENT_TIMESTAMP),
    ('PROD-1001', 200, 200, 0, 0, CURRENT_TIMESTAMP),
    ('PROD-2002', 150, 150, 0, 0, CURRENT_TIMESTAMP);
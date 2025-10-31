-- 테스트 데이터 (H2 호환)
INSERT INTO products (id, name, description, price, category, created_at, updated_at) VALUES
                                                                                          ('PROD-001', 'Test Smartphone', 'Test smartphone product', 799.99, 'ELECTRONICS', NOW(), NOW()),
                                                                                          ('PROD-002', 'Test Earbuds', 'Test wireless earbuds', 129.99, 'ELECTRONICS', NOW(), NOW());

INSERT INTO inventory (product_id, total_quantity, available_quantity, reserved_quantity, version, last_updated_at) VALUES
                                                                                                                        ('PROD-001', 100, 100, 0, 0, NOW()),
                                                                                                                        ('PROD-002', 50, 50, 0, 0, NOW());
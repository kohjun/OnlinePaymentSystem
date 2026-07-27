-- 찜하기.
--
-- 고객이 판매 이벤트를 담아두고 나중에 다시 찾는다. 같은 이벤트를 두 번
-- 담는 것은 오류가 아니라 이미 담긴 상태이므로, 유니크 제약으로 중복을
-- 막고 애플리케이션은 멱등하게 처리한다.
CREATE TABLE IF NOT EXISTS wishlist_items (
    wishlist_item_id VARCHAR(64) PRIMARY KEY,
    customer_id VARCHAR(255) NOT NULL,
    sale_event_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_wishlist_customer_event UNIQUE (customer_id, sale_event_id)
);

-- 내 찜 목록 조회. 최근에 담은 것부터 보여준다.
CREATE INDEX IF NOT EXISTS ix_wishlist_customer_created
    ON wishlist_items (customer_id, created_at DESC);

-- 특정 이벤트가 몇 번 찜됐는지 세는 경로.
CREATE INDEX IF NOT EXISTS ix_wishlist_sale_event
    ON wishlist_items (sale_event_id);

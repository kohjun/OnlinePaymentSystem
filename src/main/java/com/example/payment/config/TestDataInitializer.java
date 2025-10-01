package com.example.payment.config;

import com.example.payment.infrastructure.persistence.redis.repository.CacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 테스트용 초기 데이터 설정
 * - 한정 상품 재고 Redis에 미리 등록
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TestDataInitializer implements CommandLineRunner {

    private final CacheService cacheService;

    @Override
    public void run(String... args) throws Exception {
        log.info("========================================");
        log.info("테스트 데이터 초기화 시작");
        log.info("========================================");

        initializeLimitedProductInventory();

        log.info("========================================");
        log.info("테스트 데이터 초기화 완료");
        log.info("========================================");
    }

    /**
     * 한정 상품 재고 초기화 (Redis)
     */
    private void initializeLimitedProductInventory() {
        // 테스트 상품 1: 초특가 스마트폰 (재고 3개만)
        Map<String, Object> limitedPhone = new HashMap<>();
        limitedPhone.put("product_id", "PROD-001");
        limitedPhone.put("product_name", "초특가 스마트폰");
        limitedPhone.put("quantity", 3);        // 총 재고 3개
        limitedPhone.put("reserved", 0);        // 예약된 수량
        limitedPhone.put("price", "799.99");

        cacheService.cacheMapData("inventory:PROD-001", limitedPhone, 86400);
        log.info("✅ 한정 상품 등록: PROD-001 (초특가 스마트폰) - 재고 3개");

        // 테스트 상품 2: 프리미엄 이어버드 (재고 5개)
        Map<String, Object> limitedEarbuds = new HashMap<>();
        limitedEarbuds.put("product_id", "PROD-002");
        limitedEarbuds.put("product_name", "프리미엄 이어버드");
        limitedEarbuds.put("quantity", 5);      // 총 재고 5개
        limitedEarbuds.put("reserved", 0);      // 예약된 수량
        limitedEarbuds.put("price", "129.99");

        cacheService.cacheMapData("inventory:PROD-002", limitedEarbuds, 86400);
        log.info("✅ 한정 상품 등록: PROD-002 (프리미엄 이어버드) - 재고 5개");

        // 현재 재고 상태 출력
        logCurrentInventoryStatus("PROD-001");
        logCurrentInventoryStatus("PROD-002");
    }

    /**
     * 현재 재고 상태 로깅
     */
    private void logCurrentInventoryStatus(String productId) {
        String key = "inventory:" + productId;
        Object data = cacheService.getCachedData(key);

        if (data != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> inventory = (Map<String, Object>) data;

            int total = Integer.parseInt(inventory.get("quantity").toString());
            int reserved = Integer.parseInt(inventory.get("reserved").toString());
            int available = total - reserved;

            log.info("📦 [{}] 총재고={}, 예약중={}, 구매가능={}",
                    productId, total, reserved, available);
        }
    }
}
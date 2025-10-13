package com.example.payment.config;

import com.example.payment.domain.model.inventory.Inventory;
import com.example.payment.domain.model.inventory.Product;
import com.example.payment.domain.repository.InventoryRepository;
import com.example.payment.domain.repository.ProductRepository;
import com.example.payment.infrastructure.persistence.redis.repository.CacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class TestDataInitializer implements CommandLineRunner {

    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final CacheService cacheService;

    @Override
    @Transactional
    public void run(String... args) {
        log.info("========================================");
        log.info("테스트 데이터 초기화 시작");
        log.info("========================================");

        initializeLimitedProductInventory();

        log.info("========================================");
        log.info("테스트 데이터 초기화 완료");
        log.info("========================================");
    }

    private void initializeLimitedProductInventory() {
        // 한정 수량 상품 1
        createLimitedProduct("PROD-001", "초특가 스마트폰",
                "오늘만 특가! 선착순 3대 한정", new BigDecimal("999000"), 3);

        // 한정 수량 상품 2
        createLimitedProduct("PROD-002", "프리미엄 이어버드",
                "초특가 한정판! 5개 한정", new BigDecimal("199000"), 5);
    }

    private void createLimitedProduct(String productId, String name,
                                      String description, BigDecimal price,
                                      int quantity) {
        // 상품 등록
        Product product = Product.builder()
                .id(productId)
                .name(name)
                .description(description)
                .price(price)
                .category("LIMITED_EDITION")
                .build();
        productRepository.save(product);

        // 재고 등록
        Inventory inventory = Inventory.builder()
                .productId(productId)
                .totalQuantity(quantity)
                .availableQuantity(quantity)
                .reservedQuantity(0)
                .build();
        inventoryRepository.save(inventory);

        // Redis 캐시 (Hash 타입으로 저장)
        Map<String, Object> inventoryData = new HashMap<>();
        inventoryData.put("productId", productId);
        inventoryData.put("totalQuantity", quantity);
        inventoryData.put("availableQuantity", quantity);
        inventoryData.put("reservedQuantity", 0);

        cacheService.cacheMapData(
                "inventory:" + productId,
                inventoryData,
                Duration.ofHours(1)
        );

        log.info("✅ 한정 상품 등록: {} ({}) - 재고 {}개",
                productId, name, quantity);

        logCurrentInventoryStatus(productId);
    }

    private void logCurrentInventoryStatus(String productId) {
        Map<String, Object> cachedInventory =
                cacheService.getCachedData("inventory:" + productId);

        if (cachedInventory != null && !cachedInventory.isEmpty()) {
            log.info("📦 [{}] 총재고={}, 예약중={}, 구매가능={}",
                    productId,
                    cachedInventory.get("totalQuantity"),
                    cachedInventory.get("reservedQuantity"),
                    cachedInventory.get("availableQuantity"));
        } else {
            log.warn("⚠️ [{}] 캐시에서 재고 정보를 찾을 수 없음", productId);
        }
    }
}
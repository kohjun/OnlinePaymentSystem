package com.example.payment.infrastructure.config;

import com.example.payment.domain.model.inventory.Inventory;
import com.example.payment.domain.model.inventory.Product;
import com.example.payment.domain.repository.InventoryRepository;
import com.example.payment.domain.repository.ProductRepository;
import com.example.payment.infrastructure.util.ResourceReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class StartupDataInitializer implements ApplicationRunner {

    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final ResourceReservationService resourceReservationService;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedProduct("PROD-001", "Test Smartphone", "Test smartphone product",
                new BigDecimal("799.99"), "ELECTRONICS", 100);
        seedProduct("PROD-002", "Test Earbuds", "Test wireless earbuds",
                new BigDecimal("129.99"), "ELECTRONICS", 50);
        seedProduct("SAGA-TEST-001", "Saga Test Product", "Temporal reservation test product",
                new BigDecimal("100.00"), "TEST", 200);
        seedProduct("CONCERT-VIP", "PSY Summer Swag - VIP Seat", "Concert VIP Ticket",
                new BigDecimal("150000.00"), "CONCERT", 24);
        seedProduct("CONCERT-R", "PSY Summer Swag - R Seat", "Concert R Ticket",
                new BigDecimal("120000.00"), "CONCERT", 36);
        seedProduct("CONCERT-S", "PSY Summer Swag - S Seat", "Concert S Ticket",
                new BigDecimal("90000.00"), "CONCERT", 48);
        seedProduct("DRAW-NIKE", "나이키 에어 조던 1 레트로 한정판 드로우", "Air Jordan 1 Retro High",
                new BigDecimal("239000.00"), "DRAW", 10);
        seedProduct("AUCTION-ROLEX", "빈티지 롤렉스 서브마리너 경매", "Vintage Rolex Submariner",
                new BigDecimal("8500000.00"), "AUCTION", 1);

        List.of("PROD-001", "PROD-002", "SAGA-TEST-001", "CONCERT-VIP", "CONCERT-R", "CONCERT-S", "DRAW-NIKE", "AUCTION-ROLEX").forEach(productId ->
                inventoryRepository.findById(productId).ifPresent(inventory ->
                        initializeRedisInventoryIfMissing(
                                productId,
                                inventory.getTotalQuantity(),
                                inventory.getAvailableQuantity()
                        )));
    }

    private void initializeRedisInventoryIfMissing(String productId, int total, int available) {
        String resourceKey = "inventory:" + productId;
        Map<String, Object> current = resourceReservationService.getResourceStatus(resourceKey);
        if (current.isEmpty()) {
            resourceReservationService.initializeResource(resourceKey, total, available);
            return;
        }
        log.info("Redis inventory already exists, skipping initialization: key={}", resourceKey);
    }

    private void seedProduct(String id, String name, String description,
                             BigDecimal price, String category, int quantity) {
        productRepository.findById(id).orElseGet(() -> productRepository.save(Product.builder()
                .id(id)
                .name(name)
                .description(description)
                .price(price)
                .category(category)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build()));

        inventoryRepository.findById(id).orElseGet(() -> inventoryRepository.save(Inventory.builder()
                .productId(id)
                .totalQuantity(quantity)
                .availableQuantity(quantity)
                .reservedQuantity(0)
                .version(0L)
                .lastUpdatedAt(LocalDateTime.now())
                .build()));

        log.info("Seed data ready: productId={}, quantity={}", id, quantity);
    }
}
